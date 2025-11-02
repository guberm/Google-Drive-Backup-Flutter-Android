package dev.guber.gdrivebackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.guber.gdrivebackup/wakelock"
    private var wakeLock: PowerManager.WakeLock? = null
    private val NOTIFICATION_CHANNEL_ID = "backup_channel"
    private val BACKUP_NOTIFICATION_ID = 1

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        createNotificationChannel()
        
        // Event channel for streaming native backup progress/events
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.guber.gdrivebackup/events").setStreamHandler(object: EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                BackupEventBus.attach(events)
            }
            override fun onCancel(arguments: Any?) {
                BackupEventBus.attach(null)
            }
        })
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setWakeLock" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    setWakeLock(enabled)
                    // Foreground service owns the persistent notification; avoid duplicates
                    if (!enabled) {
                        hideBackupNotification()
                    }
                    result.success(null)
                }
                "updateProgress" -> {
                    val current = call.argument<Int>("current") ?: 0
                    val total = call.argument<Int>("total") ?: 1
                    val status = call.argument<String>("status") ?: "Backup in progress..."
                    updateNotificationProgress(current, total, status)
                    result.success(null)
                }
                "requestBatteryOptimization" -> {
                    requestBatteryOptimizationExemption()
                    result.success(null)
                }
                "requestBackgroundActivity" -> {
                    requestBackgroundActivityPermission()
                    result.success(null)
                }
                "requestNotificationPermission" -> {
                    requestNotificationPermission()
                    result.success(null)
                }
                "isBackgroundUnrestricted" -> {
                    val isUnrestricted = isBackgroundUnrestricted()
                    result.success(isUnrestricted)
                }
                "requestStoragePermission" -> {
                    requestStoragePermission()
                    result.success(null)
                }
                "requestUnrestrictedBattery" -> {
                    requestUnrestrictedBatteryUsage()
                    result.success(null)
                }
                "startBackupService" -> {
                    val intent = Intent(this, BackupForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    result.success(true)
                }
                "startNativeBackup" -> {
                    val root = call.argument<String>("root")
                    val maxMb = call.argument<Int>("maxMb") ?: 200
                    val reverify = call.argument<Boolean>("reverify") ?: false
                    val deviceId = call.argument<String>("deviceId")
                    val intent = Intent(this, BackupForegroundService::class.java).apply {
                        putExtra("native_root", root)
                        putExtra("native_max", maxMb)
                        putExtra("native_reverify", reverify)
                        if (deviceId != null) putExtra("native_device_id", deviceId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    result.success(true)
                }
                "stopBackupService" -> {
                    stopService(Intent(this, BackupForegroundService::class.java))
                    result.success(true)
                }
                "updateServiceProgress" -> {
                    // Avoid spawning duplicate service instances; directly update existing notification
                    val current = call.argument<Int>("current") ?: 0
                    val total = call.argument<Int>("total") ?: 0
                    val status = call.argument<String>("status") ?: "Backing up..."
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    // Build lightweight notification mirroring service format if running
                    if (BackupForegroundService.isRunning) {
                        val pct = if (total > 0) (current * 100 / total).coerceIn(0,100) else 0
                        val builder = androidx.core.app.NotificationCompat.Builder(this, BackupForegroundService.CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_sys_upload)
                            .setContentTitle("Drive Backup")
                            .setContentText(status)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                        if (total > 0) {
                            builder.setProgress(100, pct, false)
                        }
                        mgr.notify(BackupForegroundService.NOTIF_ID, builder.build())
                    }
                    result.success(true)
                }
                "isBackupServiceRunning" -> {
                    result.success(BackupForegroundService.isRunning)
                }
                "getBackupServiceHeartbeat" -> {
                    val prefs = getSharedPreferences("backup_service_state", Context.MODE_PRIVATE)
                    val ts = prefs.getLong("service_heartbeat", 0L)
                    result.success(ts)
                }
                "setUserStopped" -> {
                    val stopped = call.argument<Boolean>("stopped") ?: false
                    val prefs = getSharedPreferences("backup_service_state", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("user_stopped", stopped).apply()
                    result.success(true)
                }
                "wasUserStopped" -> {
                    val prefs = getSharedPreferences("backup_service_state", Context.MODE_PRIVATE)
                    result.success(prefs.getBoolean("user_stopped", false))
                }
                "setAuthHeaders" -> {
                    val headers = call.argument<Map<String, String>>("headers")
                    if (headers != null) {
                        val prefs = getSharedPreferences("backup_service_state", Context.MODE_PRIVATE)
                        val json = org.json.JSONObject(headers).toString()
                        prefs.edit().putString("auth_headers_json", json).apply()
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGS", "Headers map is null", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Backup Progress"
            val descriptionText = "Shows backup progress and status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT // Changed to DEFAULT for visibility
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true) // Show badge for better visibility
            }
            
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showBackupNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive Backup")
            .setContentText("Backup in progress...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setSilent(false) // Allow sound/vibration for better visibility
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(BACKUP_NOTIFICATION_ID, notification)
    }

    private fun hideBackupNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(BACKUP_NOTIFICATION_ID)
    }

    private fun updateNotificationProgress(current: Int, total: Int, status: String) {
        val progress = if (total > 0) (current * 100) / total else 0
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive Backup ($current/$total)")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setSilent(false)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(BACKUP_NOTIFICATION_ID, notification)
    }

    private fun setWakeLock(enabled: Boolean) {
        if (enabled) {
            // Only acquire a CPU wake lock for background processing (no screen wake)
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DriveBackup::BackupWakeLock"
            )
            wakeLock?.acquire(30 * 60 * 1000L /* 30 minutes max */)
        } else {
            // Release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                android.util.Log.d("DriveBackup", "Requesting unrestricted battery usage for reliable backups")
                
                try {
                    // Request unrestricted battery usage (not just exemption from optimization)
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("DriveBackup", "Could not open battery settings, trying app-specific settings", e)
                    // Fallback to app-specific settings
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e2: Exception) {
                        android.util.Log.e("DriveBackup", "Could not open any battery settings", e2)
                    }
                }
            } else {
                android.util.Log.d("DriveBackup", "Battery optimization already disabled - background backup should work well")
            }
        }
    }
    
    private fun requestBackgroundActivityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+ - direct to app-specific settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13+ - request notification permission at runtime
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
        } else {
            android.util.Log.d("DriveBackup", "Notification permission not required for this Android version")
        }
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - request MANAGE_EXTERNAL_STORAGE permission
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 - request standard storage permissions
            requestPermissions(arrayOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
            ), 1002)
        }
    }
    
    private fun isBackgroundUnrestricted(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        return when {
            Build.VERSION.SDK_INT >= 35 -> {
                // Android 15+ (API 35) - stricter background processing
                val batteryOptimized = powerManager.isIgnoringBatteryOptimizations(packageName)
                val backgroundAllowed = isBackgroundAppRefreshEnabled()
                batteryOptimized && backgroundAllowed
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-14 - check battery optimization
                powerManager.isIgnoringBatteryOptimizations(packageName)
            }
            else -> {
                // Older Android versions
                true
            }
        }
    }
    
    private fun requestUnrestrictedBatteryUsage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.util.Log.d("DriveBackup", "Opening battery settings to set unrestricted usage")
                
                // Direct intent to request ignoring battery optimizations (unrestricted mode)
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("DriveBackup", "Could not open battery optimization request", e)
                // Fallback to general app settings where user can set battery to unrestricted
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    android.util.Log.e("DriveBackup", "Could not open app settings", e2)
                }
            }
        }
    }
    
    private fun isBackgroundAppRefreshEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                return !activityManager.isBackgroundRestricted
            } else {
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("DriveBackup", "Could not check background app refresh status", e)
            return true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}
