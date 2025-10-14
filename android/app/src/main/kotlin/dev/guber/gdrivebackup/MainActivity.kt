package dev.guber.gdrivebackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.guber.gdrivebackup/wakelock"
    private var wakeLock: PowerManager.WakeLock? = null
    private val NOTIFICATION_CHANNEL_ID = "backup_channel"
    private val BACKUP_NOTIFICATION_ID = 1

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        createNotificationChannel()
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setWakeLock" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    setWakeLock(enabled)
                    if (enabled) {
                        showBackupNotification()
                    } else {
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
                // Only log the request - don't automatically open settings
                android.util.Log.d("DriveBackup", "Battery optimization exemption not granted - backup may be limited in background")
                
                // Optionally, could show a non-intrusive notification instead of opening settings
                // User can manually grant permission if they want via device settings
            } else {
                android.util.Log.d("DriveBackup", "Battery optimization already exempted - background backup should work well")
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
