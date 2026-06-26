package com.strategy.booster.notif

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.strategy.booster.R

object DownloadNotifier {

    fun showOrUpdate(
        context: Context,
        id: Long,
        title: String,
        downloaded: Long,
        total: Long?,
        isPaused: Boolean
    ) {
        val percent = if (total != null && total > 0) {
            ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        } else null

        val text = buildString {
            append(if (isPaused) "Paused • " else "Downloading • ")
            append(human(downloaded))
            total?.let { append(" / ${human(it)}") }
        }

        val builder = NotificationCompat.Builder(context, DownloadNotifConst.CHANNEL_ID)
            .setSmallIcon(R.drawable.notif_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!isPaused)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (percent != null) {
                    setProgress(100, percent, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .addAction(
                if (isPaused) R.drawable.play else R.drawable.pause,
                if (isPaused) "Resume" else "Pause",
                actionPI(
                    context,
                    if (isPaused) DownloadNotifConst.ACTION_RESUME else DownloadNotifConst.ACTION_PAUSE,
                    id
                )
            )
            .addAction(
                R.drawable.exit, "Exit",
                actionPI(context, DownloadNotifConst.ACTION_EXIT, id)
            )

        safeNotify(context, notifId(id), builder.build())
    }

    fun cancel(context: Context, id: Long) {
        safeCancel(context, notifId(id))
    }

    private fun actionPI(ctx: Context, action: String, id: Long): PendingIntent {
        val intent = Intent(ctx, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(DownloadNotifConst.EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            ctx,
            (id xor action.hashCode().toLong()).toInt(),
            intent,
            flags
        )
    }

    private fun notifId(id: Long) = DownloadNotifConst.NOTIF_ID_BASE + (id and 0x7FFFFFFF).toInt()

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private fun canPost(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun safeNotify(context: Context, id: Int, n: android.app.Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) { /* ignored */
        }
    }

    private fun safeCancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: SecurityException) { /* ignored */
        }
    }

}
