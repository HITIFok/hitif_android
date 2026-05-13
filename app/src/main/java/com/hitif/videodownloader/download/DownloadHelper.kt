package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType

object DownloadHelper {

    private const val TAG = "HITIF_DL"
    // Minimum storage required to start a download (100 MB)
    private const val MIN_STORAGE_MB = 100L

    fun enqueue(context: Context, item: MediaItem): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val safeName = sanitizeFilename(item.filename)

        val subPath = when (item.mediaType) {
            MediaType.AUDIO -> "HITIF/Audio/$safeName"
            else            -> "HITIF/Video/$safeName"
        }

        // ── Storage check ────────────────────────────────────────────────
        val availableMB = getAvailableStorageMB(context)
        if (availableMB >= 0 && availableMB < MIN_STORAGE_MB) {
            Log.w(TAG, "Storage presque pleine: ${availableMB}MB disponibles")
            Toast.makeText(
                context,
                "Espace insuffisant: ${availableMB}MB libres. Minimum requis: ${MIN_STORAGE_MB}MB",
                Toast.LENGTH_LONG
            ).show()
        }

        val request = DownloadManager.Request(Uri.parse(item.url)).apply {
            setTitle(safeName)
            setDescription("HITIF Video Downloader - Telechargement en cours...")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            // Only use DownloadManager's own notification — no custom duplicate
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            allowScanningByMediaScanner()

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
        }

        val downloadId = dm.enqueue(request)
        Log.d(TAG, "Download enqueued: id=$downloadId name=$safeName storage=${availableMB}MB")
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

    /**
     * Check storage space available for downloads.
     * Returns available MB, or -1 if it cannot be determined.
     */
    fun getAvailableStorageMB(context: Context): Long {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(downloadsDir.absolutePath)
            val availableBytes = stat.availableBytes
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check storage", e)
            -1L
        }
    }

    /**
     * Get total storage capacity in MB.
     */
    fun getTotalStorageMB(context: Context): Long {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(downloadsDir.absolutePath)
            stat.totalBytes / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Get used storage in MB.
     */
    fun getUsedStorageMB(context: Context): Long {
        val total = getTotalStorageMB(context)
        val available = getAvailableStorageMB(context)
        return if (total >= 0 && available >= 0) total - available else -1L
    }

    /**
     * Get a human-readable storage info string like "2.3 GB libres / 64 GB total"
     */
    fun getStorageInfoText(context: Context): String {
        val available = getAvailableStorageMB(context)
        val total = getTotalStorageMB(context)
        return if (available < 0 || total < 0) {
            "Stockage: inconnu"
        } else {
            val availStr = if (available >= 1024) String.format("%.1f GB", available / 1024.0)
                           else "${available} MB"
            val totalStr = if (total >= 1024) String.format("%.1f GB", total / 1024.0)
                           else "${total} MB"
            "$availStr libres / $totalStr"
        }
    }

    /**
     * Check if storage is critically low (< 100 MB).
     */
    fun isStorageCriticallyLow(context: Context): Boolean {
        val available = getAvailableStorageMB(context)
        return available >= 0 && available < MIN_STORAGE_MB
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

    /** Display size: use downloaded so far for running, total for completed */
    val displayBytes: Long get() = when {
        isRunning && bytesDownloaded > 0 -> bytesDownloaded
        totalBytes > 0 -> totalBytes
        else -> bytesDownloaded
    }

    /** Human-readable size string */
    val displaySizeLabel: String get() {
        val bytes = displayBytes
        return when {
            bytes <= 0 -> "0 KB"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /** Progress label with percentage */
    val progressLabel: String get() = when {
        isComplete -> "Termine"
        isFailed -> "Echoue"
        isRunning && percent >= 0 -> "$percent%"
        isRunning -> "En cours..."
        else -> ""
    }
}
