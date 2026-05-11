package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

object DownloadHelper {

    fun enqueue(context: Context, item: MediaItem): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val safeName = item.filename
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(128)

        val subPath = when (item.mediaType) {
            MediaType.AUDIO -> "HITIF/Audio/$safeName"
            else            -> "HITIF/Video/$safeName"
        }

        val request = DownloadManager.Request(Uri.parse(item.url)).apply {
            setTitle(safeName)
            setDescription("Video Download by HITIF")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            allowScanningByMediaScanner()

            // Pass headers that help some CDNs authenticate
            if (item.pageUrl.isNotBlank()) {
                addRequestHeader("Referer", item.pageUrl)
            }
            addRequestHeader("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        }

        return dm.enqueue(request)
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
