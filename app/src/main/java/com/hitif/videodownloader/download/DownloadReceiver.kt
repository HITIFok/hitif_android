package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hitif.videodownloader.ui.DownloadHistoryActivity

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q  = DownloadManager.Query().setFilterById(id)
        val c  = dm.query(q)
        if (c != null && c.moveToFirst()) {
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val title  = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Fichier"
            val uri    = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            c.close()

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    showCompletionNotification(context, title, uri, true)
                    Toast.makeText(context, "Telecharge: $title", Toast.LENGTH_SHORT).show()
                }
                DownloadManager.STATUS_FAILED -> {
                    showCompletionNotification(context, title, null, false)
                    Toast.makeText(context, "Echec: $title", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            c?.close()
        }
    }

    private fun showCompletionNotification(context: Context, title: String, localUri: String?, success: Boolean) {
        createChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = if (localUri != null) {
            try {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(localUri), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                Intent(context, DownloadHistoryActivity::class.java)
            }
        } else {
            Intent(context, DownloadHistoryActivity::class.java)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (success) android.R.drawable.stat_sys_download_done
                     else android.R.drawable.ic_dialog_alert

        val notification = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(if (success) "Telecharge: $title" else "Echec: $title")
            .setContentText(if (success) "Appuyez pour ouvrir" else "Le telechargement a echoue")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DL_CHANNEL_ID,
                "HITIF Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Suivi des telechargements"
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
