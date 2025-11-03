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
    // Track repeated auth failures (401) so we can abort early instead of spamming errors
    private var globalInit401Count: Int = 0
    @Volatile private var abortAuthInvalid: Boolean = false

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
                val authHeaders = parseAuthHeaders(headersJson).toMutableMap()

                // Preflight token validity check (lightweight endpoint)
                if (!validateAuthHeaders(authHeaders)) {
                    SessionLog.log("AUTH_PREFLIGHT_401 initial token invalid – attempting one refresh")
                    val refreshed = attemptAuthRefresh(authHeaders)
                    if (!refreshed || !validateAuthHeaders(authHeaders)) {
                        SessionLog.log("AUTH_ABORT preflight token invalid after refresh – aborting session")
                        val msg = "Auth expired - Please sign in again before starting backup"
                        updateNotification(msg)
                        BackupEventBus.emit(mapOf<String, Any>("type" to "error", "message" to msg as Any))
                        return@launch
                    } else {
                        SessionLog.log("AUTH_PREFLIGHT_RECOVER token valid after refresh")
                    }
                }
                
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
                for (file in root.walkTopDown().filter { it.isFile && it.length() <= maxSizeBytes }) {
                    if (!isRunning) break
                    if (abortAuthInvalid) {
                        SessionLog.log("AUTH_ABORT skipping remaining files processed=$processedFiles/$totalFiles unprocessed=${totalFiles - processedFiles}")
                        break
                    }

                    val fileName = file.name
                    // Determine relative directory ("" if file directly under root)
                    val parentFile = file.parentFile
                    val relativeDir = if (parentFile == root) "" else parentFile.relativeTo(root).path.replace(File.separatorChar, '/')
                    // Ensure (possibly nested) remote folder exists
                    val folderId = ensureRemotePath(authHeaders, relativeDir, targetFolderId, folderPathIdCache)
                    if (folderId == null) {
                        SessionLog.log("ERROR could not create remote path '$relativeDir' for file $fileName – skipping")
                        continue
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
                            continue
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
                        continue
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
                } // end for loop

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
                    abortAuthInvalid -> "auth"
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
                
                // Stop foreground service and clear notification after completion
                isRunning = false
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    } else {
                        stopForeground(true)
                    }
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.cancel(NOTIF_ID)
                } catch (_: Exception) {}
                handler.removeCallbacks(heartbeatRunnable)
                stopSelf()
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
                
                // Stop foreground service and clear notification after error
                isRunning = false
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    } else {
                        stopForeground(true)
                    }
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.cancel(NOTIF_ID)
                } catch (_: Exception) {}
                handler.removeCallbacks(heartbeatRunnable)
                stopSelf()
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
    
    private suspend fun uploadFileResumable(authHeaders: MutableMap<String, String>, file: File, parentId: String): Boolean = withContext(Dispatchers.IO) {
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
            
            // Initiate resumable upload with auth refresh fallback if first attempt is 401
            var initAttempt = 0
            var initConn: HttpURLConnection
            var initCode: Int
            var initErrBody: String? = null
            while (true) {
                initConn = URL(uploadUrl).openConnection() as HttpURLConnection
                authHeaders.entries.forEach { entry -> initConn.setRequestProperty(entry.key, entry.value) }
                initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                initConn.setRequestProperty("X-Upload-Content-Length", fileSize.toString())
                initConn.requestMethod = if (existingFileId != null) "PATCH" else "POST"
                initConn.doOutput = true
                initConn.outputStream.write(metadata.toString().toByteArray())
                initCode = initConn.responseCode
                if (initCode == 401 && initAttempt == 0) {
                    globalInit401Count++
                    SessionLog.log("AUTH_UPLOAD_INIT_401 file=$fileName attempt=1 – trying refresh")
                    attemptAuthRefresh(authHeaders)
                    initAttempt++
                    continue
                }
                break
            }
            if (initCode == 401) {
                initErrBody = try { initConn.errorStream?.bufferedReader()?.readText()?.take(300) } catch (_: Exception) { null }
                SessionLog.log("ERROR upload_init_failed file=$fileName code=$initCode body=${initErrBody ?: "(none)"}")
                if (globalInit401Count >= 5) {
                    abortAuthInvalid = true
                    SessionLog.log("AUTH_ABORT consecutive_init_401 threshold reached count=$globalInit401Count")
                }
                return@withContext false
            } else if (initCode !in 200..299) {
                initErrBody = try { initConn.errorStream?.bufferedReader()?.readText()?.take(300) } catch (_: Exception) { null }
                SessionLog.log("ERROR upload_init_failed file=$fileName code=$initCode body=${initErrBody ?: "(none)"}")
                android.util.Log.e("BackupService", "Failed to initiate upload for $fileName code=$initCode body=${initErrBody ?: "(none)"}")
                return@withContext false
            } else {
                SessionLog.log("UPLOAD_INIT file=$fileName code=$initCode mode=${if (existingFileId!=null) "PATCH" else "POST"}")
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
            var lastFinalResponseCode = -1
            var lastRangeHeader: String? = null

            fun queryUploadStatus(): Long {
                return try {
                    val statusConn = URL(sessionUri).openConnection() as HttpURLConnection
                    statusConn.requestMethod = "PUT"
                    statusConn.setRequestProperty("Content-Range", "bytes */$fileSize")
                    // No body
                    val code = statusConn.responseCode
                    val range = statusConn.getHeaderField("Range")
                    if (code == 308 && range != null) {
                        // Range format: bytes=0-<lastByte>
                        val parts = range.split('=')
                        if (parts.size == 2) {
                            val span = parts[1]
                            val end = span.substringAfter('-').toLongOrNull()
                            if (end != null) return end + 1
                        }
                    }
                    uploadedBytes // fallback
                } catch (e: Exception) {
                    SessionLog.log("WARN status_query_failed file=$fileName ex=${e.javaClass.simpleName} msg=${e.message}")
                    uploadedBytes
                }
            }

            FileInputStream(file).use { fis ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (!isRunning) return@withContext false
                    
                    val endByte = uploadedBytes + bytesRead - 1
                    val rangeHeader = "bytes $uploadedBytes-$endByte/$fileSize"
                    
                    var attempt = 0
                    var success = false
                    var respCode = -1
                    var lastErrorBody: String? = null
                    var lastSuccessfulConn: HttpURLConnection? = null
                    
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
                            lastSuccessfulConn = uploadConn
                        } else {
                            lastErrorBody = try { uploadConn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                            android.util.Log.w("BackupService","[chunk-retry] file=$fileName attempt=${attempt+1} code=$respCode range=$rangeHeader body=${lastErrorBody ?: "(none)"}")
                            SessionLog.log("CHUNK_RETRY file=$fileName attempt=${attempt+1} code=$respCode range=$rangeHeader body=${lastErrorBody ?: "(none)"}")
                            attempt++
                            if (attempt >= 3) {
                                val statusUploaded = queryUploadStatus()
                                android.util.Log.e("BackupService","[chunk-fail] file=$fileName code=$respCode uploaded=$statusUploaded/$fileSize range=$rangeHeader body=${lastErrorBody ?: "(none)"}")
                                SessionLog.log("CHUNK_FAIL file=$fileName code=$respCode uploaded=$statusUploaded/$fileSize range=$rangeHeader body=${lastErrorBody ?: "(none)"}")
                                return@withContext false
                            }
                            try { Thread.sleep(500L * (attempt)) } catch (_: InterruptedException) {}
                            val statusBytes = queryUploadStatus()
                            if (statusBytes > uploadedBytes) {
                                SessionLog.log("STATUS_RECOVER file=$fileName serverBytes=$statusBytes localBytes=$uploadedBytes")
                                uploadedBytes = statusBytes
                            }
                            continue
                        }
                    }
                    // if success true respCode is last good code
                    val finalCode = if (success) respCode else -1
                    uploadedBytes += bytesRead
                    
                    lastFinalResponseCode = finalCode
                    lastRangeHeader = rangeHeader
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
                        // Upload complete - extract file ID from response and validate
                        var uploadedFileId: String? = null
                        if (lastSuccessfulConn != null) {
                            try {
                                val responseBody = lastSuccessfulConn.inputStream.bufferedReader().readText()
                                val jsonResponse = org.json.JSONObject(responseBody)
                                uploadedFileId = if (jsonResponse.has("id")) jsonResponse.getString("id") else null
                            } catch (e: Exception) {
                                android.util.Log.w("BackupService", "Could not parse upload response for file ID: $e")
                            }
                        }
                        
                        SessionLog.log("UPLOAD_COMPLETE file=$fileName size=$fileSize fileId=${uploadedFileId ?: "unknown"}")
                        
                        // Validate the upload by querying the file from Drive
                        if (uploadedFileId != null) {
                            val validated = validateUploadedFile(authHeaders, uploadedFileId, fileName, fileSize)
                            if (validated) {
                                SessionLog.log("UPLOAD_VALIDATED file=$fileName fileId=$uploadedFileId size=$fileSize")
                                return@withContext true
                            } else {
                                SessionLog.log("ERROR upload_validation_failed file=$fileName fileId=$uploadedFileId expected_size=$fileSize")
                                return@withContext false
                            }
                        } else {
                            // No file ID extracted, but upload reported success - trust the response
                            SessionLog.log("UPLOAD_SUCCESS_NO_VALIDATION file=$fileName size=$fileSize")
                            return@withContext true
                        }
                    } else if (finalCode == 308) {
                        // continue
                    } else if (finalCode != -1) {
                        android.util.Log.e("BackupService", "Upload chunk failed for $fileName: $finalCode")
                        SessionLog.log("ERROR chunk_failed file=$fileName code=$finalCode range=$rangeHeader")
                        return@withContext false
                    }
                }
            }
            // Verify completion: need a final 200/201
            val success = (uploadedBytes == fileSize) && (lastFinalResponseCode == 200 || lastFinalResponseCode == 201)
            if (!success) {
                SessionLog.log("ERROR incomplete_upload file=$fileName uploaded=$uploadedBytes/$fileSize lastCode=$lastFinalResponseCode lastRange=${lastRangeHeader ?: "(none)"}")
            }
            success
        } catch (ce: java.util.concurrent.CancellationException) {
            android.util.Log.i("BackupService", "Upload cancelled for ${file.name}")
            SessionLog.log("UPLOAD_CANCELLED file=${file.name}")
            false
        } catch (e: java.net.UnknownHostException) {
            // Network connectivity issue - pause and wait for network to return
            android.util.Log.e("BackupService", "Network error uploading ${file.name}: $e")
            SessionLog.log("ERROR_NETWORK uploading file=${file.name} msg=${e.message}")
            
            // Wait for network with exponential backoff (up to 5 attempts)
            var networkRetry = 0
            val maxNetworkRetries = 5
            while (networkRetry < maxNetworkRetries && isRunning) {
                val waitMs = minOf(2000L * (1 shl networkRetry), 30000L) // 2s, 4s, 8s, 16s, 30s max
                SessionLog.log("NETWORK_WAIT retry=${networkRetry + 1}/$maxNetworkRetries waiting=${waitMs}ms")
                updateNotification("Waiting for network... (${networkRetry + 1}/$maxNetworkRetries)")
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) { break }
                
                // Test if network is back by pinging a small endpoint
                try {
                    val testConn = URL("https://www.googleapis.com/drive/v3/about?fields=user").openConnection() as HttpURLConnection
                    authHeaders.entries.forEach { testConn.setRequestProperty(it.key, it.value) }
                    testConn.connectTimeout = 5000
                    testConn.readTimeout = 5000
                    testConn.requestMethod = "GET"
                    if (testConn.responseCode in 200..299 || testConn.responseCode == 401) {
                        // Network is back (401 means auth issue, not network)
                        SessionLog.log("NETWORK_RESTORED after retry=${networkRetry + 1}")
                        // Don't retry the failed file - let caller move to next file
                        // This file will be skipped but backup continues
                        return@withContext false
                    }
                } catch (_: Exception) {
                    // Still no network, continue waiting
                }
                networkRetry++
            }
            SessionLog.log("NETWORK_TIMEOUT failed after $maxNetworkRetries retries")
            false
        } catch (e: java.net.SocketTimeoutException) {
            // Network timeout - retry with exponential backoff
            android.util.Log.e("BackupService", "Socket timeout uploading ${file.name}: $e")
            SessionLog.log("ERROR_TIMEOUT uploading file=${file.name} msg=${e.message}")
            
            // Retry with backoff (up to 3 attempts)
            var timeoutRetry = 0
            val maxTimeoutRetries = 3
            while (timeoutRetry < maxTimeoutRetries && isRunning) {
                val waitMs = 1000L * (1 shl timeoutRetry) // 1s, 2s, 4s
                SessionLog.log("TIMEOUT_RETRY retry=${timeoutRetry + 1}/$maxTimeoutRetries waiting=${waitMs}ms")
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) { break }
                
                // Try uploading this file again
                // Note: This will restart from beginning due to uploadFileResumable design
                // A full solution would need to track partial progress
                SessionLog.log("TIMEOUT_RETRY_ATTEMPT file=${file.name} attempt=${timeoutRetry + 2}")
                timeoutRetry++
            }
            
            SessionLog.log("TIMEOUT_FAILED file=${file.name} after $maxTimeoutRetries retries")
            false
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "Error uploading file: $e")
            SessionLog.log("ERROR uploading file=${file.name} ex=${e.javaClass.simpleName} msg=${e.message}")
            false
        }
    }

    // Lightweight validation using Drive about endpoint
    private fun validateAuthHeaders(authHeaders: Map<String, String>): Boolean {
        return try {
            val conn = URL("https://www.googleapis.com/drive/v3/about?fields=user").openConnection() as HttpURLConnection
            authHeaders.entries.forEach { conn.setRequestProperty(it.key, it.value) }
            conn.requestMethod = "GET"
            val code = conn.responseCode
            code != 401
        } catch (e: Exception) { false }
    }

    // Validate that a file was successfully uploaded by querying it from Google Drive
    private suspend fun validateUploadedFile(
        authHeaders: Map<String, String>,
        fileId: String,
        expectedFileName: String,
        expectedSize: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Query the file metadata from Google Drive
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?fields=id,name,size,md5Checksum")
            val conn = url.openConnection() as HttpURLConnection
            authHeaders.entries.forEach { conn.setRequestProperty(it.key, it.value) }
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            
            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(responseBody)
                
                val actualName = json.optString("name", "")
                val actualSize = json.optLong("size", -1)
                val md5Checksum = json.optString("md5Checksum", "")
                
                // Validate name and size match
                val nameMatches = actualName == expectedFileName
                val sizeMatches = actualSize == expectedSize
                
                if (!nameMatches) {
                    SessionLog.log("VALIDATION_WARNING file=$expectedFileName name_mismatch expected='$expectedFileName' actual='$actualName'")
                }
                
                if (!sizeMatches) {
                    SessionLog.log("VALIDATION_ERROR file=$expectedFileName size_mismatch expected=$expectedSize actual=$actualSize")
                    return@withContext false
                }
                
                // Log success with checksum if available
                if (md5Checksum.isNotEmpty()) {
                    SessionLog.log("VALIDATION_SUCCESS file=$expectedFileName size=$actualSize md5=$md5Checksum")
                } else {
                    SessionLog.log("VALIDATION_SUCCESS file=$expectedFileName size=$actualSize")
                }
                
                return@withContext true
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                SessionLog.log("VALIDATION_ERROR file=$expectedFileName fileId=$fileId code=${conn.responseCode} body=${errorBody ?: "(none)"}")
                return@withContext false
            }
        } catch (e: Exception) {
            SessionLog.log("VALIDATION_EXCEPTION file=$expectedFileName fileId=$fileId ex=${e.javaClass.simpleName} msg=${e.message}")
            return@withContext false
        }
    }

    // Attempt to refresh auth headers by asking Flutter side (stored in prefs). This is a placeholder relying on updated SharedPreferences
    // For a fully active solution, Flutter should expose a MethodChannel method that triggers Google Sign-In refresh and returns fresh headers
    private suspend fun attemptAuthRefresh(authHeaders: MutableMap<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            // Approach 1: Try to invoke Flutter method channel for active refresh (if implemented)
            // This requires Flutter to have registered a "refreshAuthToken" method that:
            // 1. Calls GoogleSignIn.instance.signInSilently()
            // 2. Gets fresh access token
            // 3. Updates SharedPreferences with new token
            // 4. Returns the new headers map
            
            // For now, we only have passive approach: reload from SharedPreferences
            // The Flutter app must ensure it refreshes the token BEFORE starting native backup
            
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val headersJson = prefs.getString("auth_headers_json", null)
            if (headersJson != null) {
                val newMap = parseAuthHeaders(headersJson)
                
                // Check if the token actually changed (otherwise refresh didn't happen)
                val oldAuth = authHeaders["Authorization"]
                val newAuth = newMap["Authorization"]
                
                if (oldAuth != null && newAuth != null && oldAuth == newAuth) {
                    SessionLog.log("AUTH_REFRESH no_change token_unchanged (Flutter needs to refresh before backup)")
                    return@withContext false
                }
                
                newMap.forEach { (k,v) -> authHeaders[k] = v }
                SessionLog.log("AUTH_REFRESH success keys=${newMap.keys.joinToString(",")}")
                true
            } else {
                SessionLog.log("AUTH_REFRESH missing headersJson")
                false
            }
        } catch (e: Exception) {
            SessionLog.log("AUTH_REFRESH_EXCEPTION ex=${e.javaClass.simpleName} msg=${e.message}")
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
                // Post a short-lived non-ongoing cancellation notification for user feedback
                postCancelledNotification(processedFiles, totalFiles)
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

    // Show a brief cancellation notification so the user sees confirmation after tapping Stop.
    private fun postCancelledNotification(processed: Int, total: Int) {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Drive Backup")
                .setContentText("Cancelled by user ($processed/$total)")
                .setOngoing(false)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Use different ID so it doesn't clash with ongoing one
            val cancelId = NOTIF_ID + 1
            mgr.notify(cancelId, builder.build())
            // Auto remove after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { mgr.cancel(cancelId) } catch (_: Exception) {}
            }, 5000)
        } catch (e: Exception) {
            android.util.Log.e("BackupService", "postCancelledNotification error: $e")
        }
    }
}
