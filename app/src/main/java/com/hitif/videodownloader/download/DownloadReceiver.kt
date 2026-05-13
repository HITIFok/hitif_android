package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hitif.videodownloader.ui.DownloadHistoryActivity

/**
 * Receives download completion broadcasts from Android's DownloadManager.
 *
 * CRITICAL FIX: We re-query the DownloadManager to confirm the final status
 * before showing success/failure. This prevents false "Echec" toasts when
 * the download was actually still running or has already succeeded.
 */
class DownloadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HITIF_Receiver"
        const val DL_CHANNEL_ID = "hitif_download_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q  = DownloadManager.Query().setFilterById(id)
        val c  = dm.query(q)
        if (c == null || !c.moveToFirst()) {
            c?.close()
            return
        }

        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val title  = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Fichier"
        val uri    = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        c.close()

        Log.d(TAG, "Download complete: id=$id status=$status reason=$reason title=$title")

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                showCompletionNotification(context, title, uri, true)
                Toast.makeText(context, "Telecharge: $title", Toast.LENGTH_SHORT).show()
            }
            DownloadManager.STATUS_FAILED -> {
                // Check if this is a retryable error (network issue) that DownloadManager
                // might handle itself, or a genuine permanent failure
                val errorReason = translateReason(reason)
                Log.w(TAG, "Download failed: id=$id reason=$errorReason")

                showCompletionNotification(context, title, null, false, errorReason)
                Toast.makeText(context, "Echec: $title - $errorReason", Toast.LENGTH_LONG).show()
            }
            // STATUS_RUNNING, STATUS_PENDING, STATUS_PAUSED — ignore these,
            // the download is still in progress. DownloadManager sometimes sends
            // spurious ACTION_DOWNLOAD_COMPLETE for status changes.
            else -> {
                Log.d(TAG, "Download still in progress (status=$status), ignoring broadcast")
            }
        }
    }

    /**
     * Translate DownloadManager error reason codes to human-readable French messages.
     */
    private fun translateReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Impossible de reprendre"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Periphérique introuvable"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Fichier deja existant"
            DownloadManager.ERROR_FILE_ERROR -> "Erreur fichier"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Erreur de donnees HTTP"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Espace insuffisant"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Trop de redirections"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Code HTTP non supporte"
            DownloadManager.ERROR_UNKNOWN -> "Erreur inconnue"
            else -> "Erreur #$reason"
        }
    }

    private fun showCompletionNotification(
        context: Context, title: String, localUri: String?,
        success: Boolean, errorMsg: String? = null
    ) {
        createChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = if (success && localUri != null) {
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

        val contentText = when {
            success -> "Appuyez pour ouvrir"
            errorMsg != null -> "Echec: $errorMsg"
            else -> "Le telechargement a echoue"
        }

        val notification = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(if (success) "Telecharge: $title" else "Echec: $title")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
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
