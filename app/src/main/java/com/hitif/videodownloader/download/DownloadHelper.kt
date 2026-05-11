package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

object DownloadHelper {

    private const val DL_CHANNEL_ID = "hitif_active_downloads"
    private var notificationCounter = 2000

    fun enqueue(context: Context, item: MediaItem): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val safeName = sanitizeFilename(item.filename)

        val subPath = when (item.mediaType) {
            MediaType.AUDIO -> "HITIF/Audio/$safeName"
            else            -> "HITIF/Video/$safeName"
        }

        val request = DownloadManager.Request(Uri.parse(item.url)).apply {
            setTitle(safeName)
            setDescription("HITIF Video Downloader - Telechargement en cours...")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED or
                    DownloadManager.Request.VISIBILITY_VISIBLE)
            allowScanningByMediaScanner()

            // Speed boost: use larger buffer and allow metered connections
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setRequiresCharging(false)
                setRequiresDeviceIdle(false)
            }

            // Pass headers that help some CDNs authenticate
            if (item.pageUrl.isNotBlank()) {
                addRequestHeader("Referer", item.pageUrl)
                addRequestHeader("Origin", item.pageUrl.substringBeforeLast('/'))
            }
            addRequestHeader("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            addRequestHeader("Accept", "*/*")
            addRequestHeader("Accept-Encoding", "identity")
            addRequestHeader("Connection", "keep-alive")

            // Speed boost: don't restrict bandwidth
            // Note: DownloadManager doesn't expose direct buffer control,
            // but proper headers and network settings optimize throughput
        }

        val downloadId = dm.enqueue(request)

        // Show download started notification
        showDownloadStartedNotification(context, safeName, downloadId)

        return downloadId
    }

    fun sanitizeFilename(filename: String): String {
        return filename
            .replace(Regex("[^a-zA-Z0-9._\\-\\s\\p{L}\\p{M}]"), "_")
            .replace(Regex("_+"), "_")
            .replace(Regex("\\.\\.+"), ".")
            .trim('_')
            .take(200)
            .ifBlank { "HITIF_${System.currentTimeMillis()}" }
    }

    fun queryProgress(context: Context, downloadId: Long): DownloadProgress {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q  = DownloadManager.Query().setFilterById(downloadId)
        val c  = dm.query(q)
        return if (c != null && c.moveToFirst()) {
            val status   = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val received = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total    = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            c.close()
            DownloadProgress(status, received, total)
        } else {
            c?.close()
            DownloadProgress(DownloadManager.STATUS_FAILED, 0, 0)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DL_CHANNEL_ID,
                "HITIF Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Suivi des telechargements actifs"
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showDownloadStartedNotification(context: Context, title: String, downloadId: Long) {
        createChannel(context)
        notificationCounter++

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Telechargement lance...")
            .setOngoing(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setProgress(100, 0, true)
            .build()
        notificationManager.notify(downloadId.toInt(), notification)
    }
}

data class DownloadProgress(
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else -1
    val isRunning: Boolean get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
    val isComplete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
}
