package dev.guber.gdrivebackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Backup Progress"
            val descriptionText = "Shows backup progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showBackupNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive Backup")
            .setContentText("Backup in progress...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
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
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive Backup")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, false)
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
