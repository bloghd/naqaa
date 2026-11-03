package com.downloader.video_downloader_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// Keeps the process alive while a task is running and shows a progress notification.
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val heading = intent?.getStringExtra(EXTRA_HEADING) ?: HEADING_DOWNLOAD
        createChannels(this)
        val notification = buildProgressNotification(this, title, 0, heading)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val HEADING_DOWNLOAD = "جاري التحميل..."
        const val HEADING_CONVERT = "جاري إزالة الموسيقى..."

        private const val CHANNEL_PROGRESS = "downloads_progress"
        private const val CHANNEL_RESULT = "downloads_result"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADING = "heading"

        private var activeTasks = 0

        @Synchronized
        fun start(context: Context, title: String?, heading: String = HEADING_DOWNLOAD) {
            activeTasks++
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADING, heading)
            ContextCompat.startForegroundService(context, intent)
        }

        // The service stops only when the last running task finishes
        @Synchronized
        fun stop(context: Context) {
            activeTasks = (activeTasks - 1).coerceAtLeast(0)
            if (activeTasks == 0) {
                context.stopService(Intent(context, DownloadService::class.java))
            }
        }

        fun updateProgress(
            context: Context,
            title: String?,
            percent: Int,
            heading: String = HEADING_DOWNLOAD
        ) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildProgressNotification(context, title, percent, heading))
        }

        fun showResult(
            context: Context,
            title: String?,
            success: Boolean,
            successText: String = "تم التحميل",
            failText: String = "تعذّر إكمال التحميل",
            notificationId: Int = RESULT_NOTIFICATION_ID
        ) {
            createChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setContentTitle(if (success) successText else failText)
                .setContentText(title?.takeIf { it.isNotBlank() } ?: "الملف")
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "المهام الجارية", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "نتيجة المهام", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun contentIntent(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            return PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun buildProgressNotification(
            context: Context,
            title: String?,
            percent: Int,
            heading: String
        ): Notification {
            return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(heading)
                .setContentText(title?.takeIf { it.isNotBlank() } ?: "الملف")
                .setProgress(100, percent, percent <= 0)
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
