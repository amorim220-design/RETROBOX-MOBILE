package com.retrobox.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.retrobox.MainActivity
import com.retrobox.R

/** Canal e notificação do download em andamento (foreground service). */
object DownloadNotifications {

    const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 0x524F4D // "ROM"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.download_channel_description)
                setShowBadge(false)
            }
        )
    }

    fun progress(context: Context, title: String, done: Long, total: Long): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.download_notification_title, title))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)

        if (total > 0) {
            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
                .setContentText("$percent% — ${mb(done)} de ${mb(total)}")
        } else {
            builder.setContentText(mb(done))
        }
        return builder.build()
    }

    fun notificationId(): Int = NOTIFICATION_ID

    private fun mb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
