package dev.guber.gdrivebackup

import android.content.Context
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * Enhanced session logger.
 * Features:
 *  - Ring buffer (MAX_LINES)
 *  - Log levels (INFO/DEBUG) with filtering
 *  - Persist to file on demand with optional gzip compression (>64KB)
 *  - Retention: keep most recent MAX_HISTORY matching prefix, delete older
 *  - Status suffix via caller-provided filename (e.g. session_log_<ts>_ok.txt)
 */
object SessionLog {
    enum class Level { INFO, DEBUG }

    private const val MAX_LINES = 4000
    private const val COMPRESS_THRESHOLD_BYTES = 64 * 1024 // 64 KB
    private const val MAX_HISTORY = 30
    private const val PREFS_NAME = "backup_service_state"
    private const val PREF_LOG_LEVEL = "log_level"

    @Volatile private var currentLevel: Level = Level.INFO
    private var levelLoaded = false

    private val lines = ArrayList<String>(MAX_LINES)
    private val dateFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ------------ Level Management ------------
    @Synchronized fun setLevel(ctx: Context, level: String): Boolean {
        return try {
            val lv = Level.valueOf(level.uppercase(Locale.US))
            currentLevel = lv
            levelLoaded = true
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_LOG_LEVEL, lv.name).apply()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun getLevel(ctx: Context?): String {
        if (!levelLoaded && ctx != null) {
            val v = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LOG_LEVEL, Level.INFO.name)
            try { currentLevel = Level.valueOf(v!!) } catch (_: Exception) {}
            levelLoaded = true
        }
        return currentLevel.name
    }

    // ------------ Logging ------------
    @Synchronized fun log(message: String) { addLine("INFO", message) }
    @Synchronized fun debug(message: String) {
        if (currentLevel == Level.DEBUG) addLine("DEBUG", message)
    }

    @Synchronized private fun addLine(tag: String, message: String) {
        val line = "${dateFmt.format(Date())} | $tag | $message"
        if (lines.size >= MAX_LINES) lines.removeAt(0)
        lines.add(line)
    }

    @Synchronized fun getLog(): String = lines.joinToString("\n")

    @Synchronized fun clear() { lines.clear() }

    // ------------ Persistence ------------
    @Synchronized fun persistToFile(dir: File, fileName: String): String? {
        return try {
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            val text = getLog()
            outFile.writeText(text)
            var finalFile = outFile
            if (outFile.length() > COMPRESS_THRESHOLD_BYTES) {
                // Compress to gzip and delete original
                val gzFile = File(dir, "$fileName.gz")
                GZIPOutputStream(FileOutputStream(gzFile)).use { gos ->
                    OutputStreamWriter(gos, Charsets.UTF_8).use { w -> w.write(text) }
                }
                outFile.delete()
                finalFile = gzFile
            }
            enforceRetention(dir)
            finalFile.name
        } catch (e: Exception) {
            android.util.Log.e("SessionLog", "persist error ${e.message}")
            null
        }
    }

    private fun enforceRetention(dir: File) {
        try {
            val logs = dir.listFiles { f -> f.name.startsWith("session_log_") }?.toList() ?: return
            if (logs.size <= MAX_HISTORY) return
            val sorted = logs.sortedByDescending { it.lastModified() }
            sorted.drop(MAX_HISTORY).forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("SessionLog", "retention error ${e.message}")
        }
    }

    // ------------ File Helpers ------------
    fun listLogFiles(dir: File): List<Triple<String, Long, Long>> { // (name,size,modified)
        return try {
            dir.listFiles { f -> f.name.startsWith("session_log_") }?.map { Triple(it.name, it.length(), it.lastModified()) }
                ?.sortedByDescending { it.third } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun readLogFile(dir: File, name: String): String? {
        return try {
            val f = File(dir, name)
            if (!f.exists()) return null
            if (f.name.endsWith(".gz")) {
                GZIPInputStream(FileInputStream(f)).use { gis ->
                    InputStreamReader(gis, Charsets.UTF_8).use { it.readText() }
                }
            } else f.readText()
        } catch (e: Exception) {
            android.util.Log.e("SessionLog", "read error ${e.message}")
            null
        }
    }
}
