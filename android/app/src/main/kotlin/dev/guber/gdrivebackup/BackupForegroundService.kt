package dev.guber.gdrivebackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.guber.gdrivebackup.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// NOTE: For full migration, actual Google Drive API client initialization with auth tokens
// should be added; this skeleton focuses on structure & progress signaling.

class BackupForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val NOTIF_ID = 1002
        const val ACTION_STOP = "STOP_BACKUP"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        @Volatile var isRunning: Boolean = false
    }

    private val heartbeatIntervalMs = 30_000L
    private val heartbeatKey = "service_heartbeat"
    private val prefsName = "backup_service_state"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().putLong(heartbeatKey, System.currentTimeMillis()).apply()
            } catch (_: Exception) {}
            if (isRunning) {
                handler.postDelayed(this, heartbeatIntervalMs)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var backupJob: Job? = null
    private var totalFiles = 0
    private var processedFiles = 0
    @Volatile private var userCancelled: Boolean = false
    private var currentStatus: String = "Initializing backup..."

    private fun scanCountFiles(dir: File, maxSizeBytes: Long): Int {
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                if (f.length() <= maxSizeBytes) count++
            } else if (f.isDirectory) {
                count += scanCountFiles(f, maxSizeBytes)
            }
        }
        return count
    }

    private fun startNativeBackup(rootPath: String, maxSizeMB: Int, reverify: Boolean = false, deviceIdShortOverride: String? = null) {
        if (backupJob?.isActive == true) return
        backupJob = serviceScope.launch {
            try {
                val startTimeMs = System.currentTimeMillis()
                SessionLog.clear()
                SessionLog.log("Backup session started root=$rootPath maxSizeMB=$maxSizeMB reverify=$reverify device=${deviceIdShortOverride}")
                var uploadedCount = 0
                var skippedHashCount = 0
                var skippedSizeCount = 0
                var errorCount = 0
                var bytesUploaded = 0L
                val root = File(rootPath)
                if (!root.exists() || !root.isDirectory) {
                    updateNotification("Folder not found")
                    BackupEventBus.emit(mapOf<String, Any>("type" to "error", "message" to "Folder not found"))
                    SessionLog.log("ERROR folder not found: $rootPath")
                    return@launch
                }
                
                // Load auth headers
                val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val headersJson = prefs.getString("auth_headers_json", null)
                if (headersJson == null) {
                    updateNotification("Auth headers missing")
                    BackupEventBus.emit(mapOf<String, Any>("type" to "error", "message" to "Auth headers not set"))
                    SessionLog.log("ERROR auth headers missing – aborting")
                    return@launch
                }
                val authHeaders = parseAuthHeaders(headersJson)
                
                val maxSizeBytes = maxSizeMB * 1024L * 1024L
                totalFiles = scanCountFiles(root, maxSizeBytes)
                processedFiles = 0
                updateNotification("Scanning complete: $totalFiles files")
                SessionLog.log("Scan complete totalFiles=$totalFiles")
                // Provide both scan_complete and initial progress with consistent keys
                val scanEvent = mapOf<String, Any>(
                    "type" to "scan_complete",
                    "total" to totalFiles,
                    "processed" to 0
                )
                BackupEventBus.emit(scanEvent)
                BackupEventBus.emit(mapOf<String, Any>(
                    "type" to "native_progress",
                    "processed" to 0,
                    "total" to totalFiles,
                    "status" to "Starting backup 0/$totalFiles"
                ))

                // Get or create device-specific AppBackup root (multi-device separation)
                // deviceId passed from intent now supplied via parameter (fix unresolved reference)
                val deviceIdShort = deviceIdShortOverride ?: "unknown"
                val deviceRootName = "AppBackup_${deviceIdShort}"
                val appBackupId = getOrCreateFolder(authHeaders, deviceRootName, null)
                if (appBackupId == null) {
                    updateNotification("Failed to create backup folder")
                    BackupEventBus.emit(mapOf<String, Any>("type" to "error", "message" to "Failed to create backup folder"))
                    SessionLog.log("ERROR failed to create/find device root folder $deviceRootName")
                    return@launch
                }
                
                // Create subfolder with selected folder name
                val folderName = root.name
                val targetFolderId = getOrCreateFolder(authHeaders, folderName, appBackupId)
                if (targetFolderId == null) {
                    updateNotification("Failed to create target folder")
                    BackupEventBus.emit(mapOf<String, Any>("type" to "error", "message" to "Failed to create target folder"))
                    SessionLog.log("ERROR failed to create/find target folder $folderName under $deviceRootName")
                    return@launch
                }
                SessionLog.log("Target root Drive folder id=$targetFolderId name=$folderName")

                // Load cached metadata (if any) for existing remote files
                val cacheFile = File(filesDir, "drive_cache_${targetFolderId}.json")
                val existingFilesMap = if (!reverify && cacheFile.exists()) {
                    android.util.Log.i("BackupService","[cache] loading cached metadata from ${cacheFile.name}")
                    readCacheMap(cacheFile)
                } else {
                    emptyMap()
                }
                var remoteMap = existingFilesMap
                if (remoteMap.isEmpty() || reverify) {
                    remoteMap = fetchExistingFilesMap(authHeaders, targetFolderId)
                    android.util.Log.i("BackupService","[prefetch] fetched remote files count=${remoteMap.size}")
                    SessionLog.log("Fetched remote top-level listing count=${remoteMap.size} reverify=$reverify")
                    writeCacheMap(cacheFile, remoteMap)
                } else {
                    android.util.Log.i("BackupService","[cache] using cached remote files count=${remoteMap.size}")
                    SessionLog.log("Using cached remote top-level metadata count=${remoteMap.size}")
                }

                // Prepare hierarchical folder + per-folder remote metadata caches
                val folderPathIdCache = HashMap<String, String>() // relativePath -> folderId ("" => targetFolderId)
                folderPathIdCache[""] = targetFolderId
                val perFolderRemoteMap = HashMap<String, MutableMap<String, Triple<String, Long, String?>>>()
                perFolderRemoteMap[targetFolderId] = remoteMap.toMutableMap()

                // Upload files preserving relative directory structure
                root.walkTopDown().filter { it.isFile && it.length() <= maxSizeBytes }.forEach { file ->
                    if (!isRunning) return@launch

                    val fileName = file.name
                    // Determine relative directory ("" if file directly under root)
                    val parentFile = file.parentFile
                    val relativeDir = if (parentFile == root) "" else parentFile.relativeTo(root).path.replace(File.separatorChar, '/')
                    // Ensure (possibly nested) remote folder exists
                    val folderId = ensureRemotePath(authHeaders, relativeDir, targetFolderId, folderPathIdCache)
                    if (folderId == null) {
                        SessionLog.log("ERROR could not create remote path '$relativeDir' for file $fileName – skipping")
                        return@forEach
                    }
                    // Acquire or fetch remote listing for this folder
                    val folderRemoteMap = perFolderRemoteMap.getOrPut(folderId) {
                        SessionLog.log("Listing remote folder for path='$relativeDir'")
                        fetchExistingFilesMap(authHeaders, folderId).toMutableMap()
                    }
                    val localSize = file.length()
                    val remoteMeta = folderRemoteMap[fileName]
                    var localMd5: String? = null
                    var md5Start: Long
                    if (remoteMeta != null && remoteMeta.second == localSize) {
                        // Compute md5 only if sizes match
                        md5Start = System.currentTimeMillis()
                        localMd5 = computeFileMd5(file)
                        SessionLog.log("MD5 time ms=${System.currentTimeMillis()-md5Start} file=$fileName path=$relativeDir")
                        if (localMd5 != null && localMd5.equals(remoteMeta.third, ignoreCase = true)) {
                            android.util.Log.i("BackupService","[skip] $fileName hash+size match processed=$processedFiles/$totalFiles")
                            SessionLog.log("SKIP hash+size match file=$fileName path=$relativeDir size=$localSize md5=$localMd5")
                            processedFiles++
                            skippedHashCount++
                            BackupEventBus.emit(mapOf<String, Any>(
                                "type" to "file_skipped",
                                "fileName" to fileName,
                                "reason" to "Already exists (size+hash match)"
                            ))
                            updateNotification("Skipping $fileName ($processedFiles/$totalFiles)", processedFiles, totalFiles, fileName)
                            BackupEventBus.emit(mapOf<String, Any>(
                                "type" to "native_progress",
                                "processed" to processedFiles,
                                "total" to totalFiles,
                                "status" to "Skipping $processedFiles/$totalFiles"
                            ))
                            return@forEach
                        }
                    }
                    if (remoteMeta != null && remoteMeta.second == localSize && remoteMeta.third.isNullOrEmpty()) {
                        android.util.Log.i("BackupService","[skip] $fileName size match (no hash available) processed=$processedFiles/$totalFiles")
                        processedFiles++
                        skippedSizeCount++
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "file_skipped",
                            "fileName" to fileName,
                            "reason" to "Already exists (size match)"
                        ))
                        SessionLog.log("SKIP size match (no hash) file=$fileName path=$relativeDir size=$localSize")
                        updateNotification("Skipping $fileName ($processedFiles/$totalFiles)", processedFiles, totalFiles, fileName)
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "native_progress",
                            "processed" to processedFiles,
                            "total" to totalFiles,
                            "status" to "Skipping $processedFiles/$totalFiles"
                        ))
                        return@forEach
                    }

                    android.util.Log.i("BackupService","[start] file=$fileName size=${file.length()} processed=$processedFiles/$totalFiles")
                    SessionLog.log("START file=$fileName path=$relativeDir size=$localSize")
                    BackupEventBus.emit(mapOf<String, Any>(
                        "type" to "file_start",
                        "fileName" to fileName,
                        "fileSize" to file.length()
                    ))
                    updateNotification("Uploading $fileName ($processedFiles/$totalFiles)", processedFiles, totalFiles, fileName)

                    val fileStartMs = System.currentTimeMillis()
                    val uploaded = uploadFileResumable(authHeaders, file, folderId).also { success ->
                        if (success) {
                            uploadedCount++
                            bytesUploaded += localSize
                            // Add to folder remote map for potential subsequent duplicate detection
                            folderRemoteMap[fileName] = Triple("?", localSize, computeFileMd5(file))
                        } else {
                            errorCount++
                        }
                    }
                    processedFiles++

                    if (uploaded) {
                        android.util.Log.i("BackupService","[done] file=$fileName processed=$processedFiles/$totalFiles")
                        SessionLog.log("DONE file=$fileName path=$relativeDir durationMs=${System.currentTimeMillis()-fileStartMs}")
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "file_done",
                            "fileName" to fileName
                        ))
                    } else {
                        android.util.Log.e("BackupService","[error] file=$fileName upload failed processed=$processedFiles/$totalFiles")
                        SessionLog.log("ERROR upload failed file=$fileName path=$relativeDir durationMs=${System.currentTimeMillis()-fileStartMs}")
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "file_error",
                            "fileName" to fileName,
                            "message" to "Upload failed"
                        ))
                    }

                    updateNotification("Uploading $fileName ($processedFiles/$totalFiles)", processedFiles, totalFiles, fileName)
                    BackupEventBus.emit(mapOf<String, Any>(
                        "type" to "native_progress",
                        "processed" to processedFiles,
                        "total" to totalFiles,
                        "status" to "Uploading $processedFiles/$totalFiles"
                    ))
                }

                android.util.Log.i("BackupService","[complete] processed=$processedFiles/$totalFiles uploaded=$uploadedCount skippedHash=$skippedHashCount skippedSize=$skippedSizeCount errors=$errorCount bytes=$bytesUploaded")
                updateNotification("Backup complete ($processedFiles/$totalFiles)")
                if (!userCancelled) {
                    BackupEventBus.emit(mapOf<String, Any>(
                        "type" to "native_complete",
                        "processed" to processedFiles,
                        "total" to totalFiles,
                        "status" to "Backup complete ($processedFiles/$totalFiles)"
                    ))
                }
                val durationMs = System.currentTimeMillis() - startTimeMs
                SessionLog.log("SUMMARY processed=$processedFiles uploaded=$uploadedCount skippedHash=$skippedHashCount skippedSize=$skippedSizeCount errors=$errorCount bytes=$bytesUploaded durationMs=$durationMs userCancelled=$userCancelled")
                val statusSuffix = when {
                    userCancelled -> "cancelled"
                    errorCount > 0 -> "err$errorCount"
                    else -> "ok"
                }
                val fileName = "session_log_${System.currentTimeMillis()}_${statusSuffix}.txt"
                SessionLog.persistToFile(filesDir, fileName)
                BackupEventBus.emit(mapOf<String, Any>(
                    "type" to "native_summary",
                    "uploadedCount" to uploadedCount,
                    "skippedHashCount" to skippedHashCount,
                    "skippedSizeCount" to skippedSizeCount,
                    "errorCount" to errorCount,
                    "bytesUploaded" to bytesUploaded,
                    "durationMs" to durationMs
                ))
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) {
                    if (!userCancelled) {
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "native_complete",
                            "processed" to processedFiles,
                            "total" to totalFiles,
                            "status" to "Cancelled by user"
                        ))
                    }
                    SessionLog.log("CANCELLED processed=$processedFiles/$totalFiles")
                } else {
                    updateNotification("Error: ${e.message}")
                    BackupEventBus.emit(mapOf<String, Any>(
                        "type" to "error",
                        "message" to (e.message ?: "Unknown error") as Any
                    ))
                    SessionLog.log("ERROR exception ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // Ensure nested remote path hierarchy (relativeDir like "sub/inner"). Returns folderId for final segment
    private suspend fun ensureRemotePath(authHeaders: Map<String, String>, relativeDir: String, rootFolderId: String, cache: MutableMap<String, String>): String? {
        if (relativeDir.isEmpty()) return rootFolderId
        val segments = relativeDir.split('/')
        var currentPath = ""
        var parentId = rootFolderId
        for (seg in segments) {
            currentPath = if (currentPath.isEmpty()) seg else "$currentPath/$seg"
            val cached = cache[currentPath]
            if (cached != null) {
                parentId = cached
                continue
            }
            val id = getOrCreateFolder(authHeaders, seg, parentId)
            if (id == null) return null
            cache[currentPath] = id
            parentId = id
            SessionLog.log("Created/Found folder '$seg' path='$currentPath' id=$id")
        }
        return parentId
    }

    // Fetch existing files metadata (name -> Triple<id,size,md5>)
    private suspend fun fetchExistingFilesMap(authHeaders: Map<String, String>, parentId: String): Map<String, Triple<String, Long, String?>> = withContext(Dispatchers.IO) {
        val result = HashMap<String, Triple<String, Long, String?>>()
        try {
            var pageToken: String? = null
            do {
                val urlStr = buildString {
                    append("https://www.googleapis.com/drive/v3/files?q=")
                    val query = "'$parentId' in parents and trashed=false"
                    append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&fields=nextPageToken,files(id,name,size,md5Checksum)")
                    if (pageToken != null) append("&pageToken=$pageToken")
                }
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                authHeaders.entries.forEach { conn.setRequestProperty(it.key, it.value) }
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    pageToken = json.optString("nextPageToken", null)
                    val files = json.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val obj = files.getJSONObject(i)
                            val id = obj.getString("id")
                            val name = obj.getString("name")
                            val size = obj.optString("size", "0").toLongOrNull() ?: 0L
                            val md5 = obj.optString("md5Checksum", null)
                            result[name] = Triple(id, size, md5)
                        }
                    }
                } else {
                    android.util.Log.e("BackupService","[prefetch] list failed code=${conn.responseCode}")
                    break
                }
            } while (!pageToken.isNullOrEmpty())
        } catch (e: Exception) {
            android.util.Log.e("BackupService","[prefetch] error $e")
        }
        result
    }

    private fun computeFileMd5(file: File): String? {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var r: Int
                while (fis.read(buf).also { r = it } > 0) {
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
        } catch (e: Exception) {
            android.util.Log.e("BackupService","[hash] error computing md5 for ${file.name}: $e")
            null
        }
    }

    // Returns Pair<fileId, size> if exists, else null
    private suspend fun findExistingFile(authHeaders: Map<String, String>, fileName: String, parentId: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        try {
            val query = "name='${fileName}' and '${parentId}' in parents and trashed=false"
            val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,size)")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            authHeaders.entries.forEach { entry -> searchConn.setRequestProperty(entry.key, entry.value) }
            searchConn.requestMethod = "GET"
            if (searchConn.responseCode == 200) {
                val response = searchConn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val obj = files.getJSONObject(0)
                    val sizeStr = obj.optString("size", "0")
                    val size = sizeStr.toLongOrNull() ?: 0L
                    return@withContext Pair(obj.getString("id"), size)
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "Error searching existing file: $e")
            null
        }
    }
    
    private fun parseAuthHeaders(json: String): Map<String, String> {
        val result = HashMap<String, String>()
        try {
            val obj = JSONObject(json)
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val key = iterator.next() as String
                result[key] = obj.getString(key)
            }
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "Error parsing auth headers: $e")
        }
        return result
    }
    
    private suspend fun getOrCreateFolder(authHeaders: Map<String, String>, folderName: String, parentId: String?): String? = withContext(Dispatchers.IO) {
        try {
            // Search for existing folder
            val query = if (parentId != null) {
                "name='$folderName' and '$parentId' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            } else {
                "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            }
            
            val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            authHeaders.entries.forEach { entry -> searchConn.setRequestProperty(entry.key, entry.value) }
            searchConn.requestMethod = "GET"
            
            if (searchConn.responseCode == 200) {
                val response = searchConn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@withContext files.getJSONObject(0).getString("id")
                }
                            updateNotification("Cancelled by user", processedFiles, totalFiles)
            }
            
            // Create folder
            val metadata = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
                if (parentId != null) {
                    put("parents", org.json.JSONArray().put(parentId))
                }
            }
            
            val createUrl = URL("https://www.googleapis.com/drive/v3/files")
            val createConn = createUrl.openConnection() as HttpURLConnection
            authHeaders.entries.forEach { entry -> createConn.setRequestProperty(entry.key, entry.value) }
            createConn.setRequestProperty("Content-Type", "application/json")
            createConn.requestMethod = "POST"
            createConn.doOutput = true
            createConn.outputStream.write(metadata.toString().toByteArray())
            
            if (createConn.responseCode == 200) {
                val response = createConn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                return@withContext json.getString("id")
            }
            
            null
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "Error creating folder: $e")
            null
        }
    }
    
    private suspend fun uploadFileResumable(authHeaders: Map<String, String>, file: File, parentId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = file.name
            val fileSize = file.length()
            
            // Check if file exists
            val query = "name='$fileName' and '$parentId' in parents and trashed=false"
            val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,modifiedTime)")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            authHeaders.entries.forEach { entry -> searchConn.setRequestProperty(entry.key, entry.value) }
            searchConn.requestMethod = "GET"
            
            var existingFileId: String? = null
            if (searchConn.responseCode == 200) {
                val response = searchConn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    existingFileId = files.getJSONObject(0).getString("id")
                }
            }
            
            // Metadata
            val metadata = JSONObject().apply {
                put("name", fileName)
                if (existingFileId == null) {
                    put("parents", org.json.JSONArray().put(parentId))
                }
            }
            
            // Initiate resumable upload
            val uploadUrl = if (existingFileId != null) {
                "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=resumable"
            } else {
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
            }
            
            val initConn = URL(uploadUrl).openConnection() as HttpURLConnection
            authHeaders.entries.forEach { entry -> initConn.setRequestProperty(entry.key, entry.value) }
            initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            initConn.setRequestProperty("X-Upload-Content-Length", fileSize.toString())
            initConn.requestMethod = if (existingFileId != null) "PATCH" else "POST"
            initConn.doOutput = true
            initConn.outputStream.write(metadata.toString().toByteArray())
            
            if (initConn.responseCode !in 200..299) {
                android.util.Log.e("BackupService", "Failed to initiate upload for $fileName: ${initConn.responseCode}")
                return@withContext false
            }
            
            val sessionUri = initConn.getHeaderField("Location")
            if (sessionUri == null) {
                android.util.Log.e("BackupService", "No session URI for $fileName")
                return@withContext false
            }
            
            // Upload file in chunks
            val chunkSize = 256 * 1024 // 256 KB
            var uploadedBytes = 0L
            
            var lastChunkEmitted = 0
            var nextPctMilestone = 10
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (!isRunning) return@withContext false
                    
                    val endByte = uploadedBytes + bytesRead - 1
                    val rangeHeader = "bytes $uploadedBytes-$endByte/$fileSize"
                    
                    var respCode = -1
                    var attempt = 0
                    var success = false
                    while (attempt < 3 && !success) {
                        val uploadConn = URL(sessionUri).openConnection() as HttpURLConnection
                        uploadConn.setRequestProperty("Content-Length", bytesRead.toString())
                        uploadConn.setRequestProperty("Content-Range", rangeHeader)
                        uploadConn.requestMethod = "PUT"
                        uploadConn.doOutput = true
                        uploadConn.outputStream.write(buffer, 0, bytesRead)
                        respCode = uploadConn.responseCode
                        if (respCode in listOf(200,201,308)) {
                            success = true
                        } else {
                            android.util.Log.w("BackupService","[chunk-retry] file=$fileName attempt=${attempt+1} code=$respCode range=$rangeHeader")
                            SessionLog.log("CHUNK_RETRY file=$fileName attempt=${attempt+1} code=$respCode range=$rangeHeader")
                            attempt++
                            if (attempt >= 3) {
                                android.util.Log.e("BackupService","[chunk-fail] file=$fileName code=$respCode range=$rangeHeader")
                                SessionLog.log("CHUNK_FAIL file=$fileName code=$respCode range=$rangeHeader")
                                return@withContext false
                            }
                            // small backoff
                            try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) {}
                            continue
                        }
                    }
                    // if success true respCode is last good code
                    val finalCode = if (success) respCode else -1
                    uploadedBytes += bytesRead
                    
                    // Emit file progress (throttle every 4 chunks)
                    val progress = uploadedBytes.toDouble() / fileSize
                    if (lastChunkEmitted % 4 == 0 || uploadedBytes == fileSize) {
                        BackupEventBus.emit(mapOf<String, Any>(
                            "type" to "file_progress",
                            "fileName" to fileName,
                            "progress" to progress,
                            "uploaded" to uploadedBytes,
                            "total" to fileSize
                        ))
                        updateNotification("Uploading $processedFiles/$totalFiles", processedFiles, totalFiles, fileName)
                    }
                    val pctInt = (progress * 100).toInt().coerceIn(0,100)
                    if (pctInt >= nextPctMilestone) {
                        SessionLog.log("CHUNK progress file=$fileName ${pctInt}% bytes=$uploadedBytes/$fileSize")
                        nextPctMilestone += 10
                    }
                    lastChunkEmitted++
                    
                    if (finalCode == 200 || finalCode == 201) {
                        // Upload complete
                        return@withContext true
                    } else if (finalCode !in 308..308) {
                        android.util.Log.e("BackupService", "Upload chunk failed for $fileName: $finalCode")
                        return@withContext false
                    }
                }
            }
            
            true
        } catch (ce: java.util.concurrent.CancellationException) {
            android.util.Log.i("BackupService", "Upload cancelled for ${file.name}")
            SessionLog.log("UPLOAD_CANCELLED file=${file.name}")
            false
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "Error uploading file: $e")
            SessionLog.log("ERROR uploading file=${file.name} ex=${e.javaClass.simpleName} msg=${e.message}")
            false
        }
    }

    private fun updateNotification(status: String, progress: Int = -1, total: Int = -1, currentFile: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(status, progress, total, currentFile))
    }

    private fun writeCacheMap(file: File, map: Map<String, Triple<String, Long, String?>>) {
        try {
            val arr = org.json.JSONArray()
            map.forEach { (name, triple) ->
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("id", triple.first)
                obj.put("size", triple.second)
                obj.put("md5", triple.third ?: JSONObject.NULL)
                arr.put(obj)
            }
            file.writeText(arr.toString())
            android.util.Log.i("BackupService","[cache] wrote ${map.size} entries to ${file.name}")
        } catch (e: Exception) {
            android.util.Log.e("BackupService","[cache] write error $e")
        }
    }

    private fun readCacheMap(file: File): Map<String, Triple<String, Long, String?>> {
        val result = HashMap<String, Triple<String, Long, String?>>()
        try {
            val txt = file.readText()
            val arr = org.json.JSONArray(txt)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val id = obj.getString("id")
                val size = obj.optLong("size", 0L)
                val md5 = obj.optString("md5", null)
                result[name] = Triple(id, size, if (md5 == "null") null else md5)
            }
            android.util.Log.i("BackupService","[cache] read ${result.size} entries from ${file.name}")
        } catch (e: Exception) {
            android.util.Log.e("BackupService","[cache] read error $e")
        }
        return result
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Initializing backup..."))
        isRunning = true
        // Start heartbeat loop
        handler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Mark user stopped so Flutter watchdog will not restart
            try {
                val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().putBoolean("user_stopped", true).apply()
                val uiPrefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                uiPrefs.edit().putBoolean("flutter.backup_in_progress", false).apply()
            } catch (_: Exception) {}

            // Stop running job and update state
            isRunning = false
            userCancelled = true
            try { backupJob?.cancel() } catch (_: Exception) {}

            // Emit cancellation event so UI can update (will be skipped in coroutine catch)
            BackupEventBus.emit(mapOf(
                "type" to "native_complete",
                "processed" to processedFiles,
                "total" to totalFiles,
                "status" to "Cancelled by user"
            ))
            SessionLog.log("ACTION_STOP received – user cancelled")

            // Remove foreground notification
            try {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                // Explicit cancel to ensure disappearance
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.cancel(NOTIF_ID)
            } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        val status = intent?.getStringExtra(EXTRA_STATUS)
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val total = intent?.getIntExtra(EXTRA_TOTAL, -1) ?: -1
        val nativeRoot = intent?.getStringExtra("native_root")
        val nativeMax = intent?.getIntExtra("native_max", -1) ?: -1
        val nativeReverify = intent?.getBooleanExtra("native_reverify", false) ?: false
        val nativeDeviceId = intent?.getStringExtra("native_device_id")
        if (nativeRoot != null && nativeMax > 0) {
            startNativeBackup(nativeRoot, nativeMax, nativeReverify, nativeDeviceId)
        }
        if (status != null && progress >= 0 && total > 0) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(status, progress, total))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Drive Backup",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Progress of background Google Drive backup"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(status: String, progress: Int = -1, total: Int = -1, currentFile: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val contentPI = PendingIntent.getActivity(this, 0, intent, piFlags)

        val stopIntent = Intent(this, BackupForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 1, stopIntent, piFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Drive Backup")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPI)
            .addAction(0, "Stop", stopPI)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (currentFile != null) {
            builder.setSubText(currentFile.take(40))
        }

        if (progress >= 0 && total > 0) {
            val pct = (progress * 100 / total).coerceIn(0, 100)
            builder.setProgress(100, pct, false)
                .setSubText("$progress/$total")
        }
        return builder.build()
    }
}
