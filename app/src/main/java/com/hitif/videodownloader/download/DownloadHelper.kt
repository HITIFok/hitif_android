package com.hitif.videodownloader.download

import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.webkit.CookieManager
import com.hitif.videodownloader.db.AppDatabase
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.network.SmartNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

/**
 * Download orchestrator — ALL downloads go through our own pipeline
 * (HlsDownloader or TurboDownloadEngine), never through Android DownloadManager.
 * Progress is tracked via Room DB + DownloadNotificationManager.
 */
object DownloadHelper {

    private const val TAG = "DownloadHelper"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var initialized = false

    /** Must be called once from Application or first Activity */
    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true
        DownloadNotificationManager.init(context)
    }

    /** Start the foreground service for notifications — called lazily */
    @Synchronized
    private fun ensureService(context: android.content.Context) {
        if (!initialized) init(context)
        try {
            DownloadProgressService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DownloadProgressService: ${e.message}")
        }
    }

    fun enqueue(context: android.content.Context, item: MediaItem): Long {
        if (!initialized) init(context)

        val naming = SmartNaming.build(item)

        return when (item.mediaType) {
            MediaType.HLS -> downloadHls(context, item, naming)
            else          -> downloadDirect(context, item, naming)
        }
    }

    fun enqueueBatch(context: android.content.Context, items: List<MediaItem>): List<Pair<MediaItem, Long>> {
        return items.map { item -> item to enqueue(context, item) }
    }

    // ========================================================================
    // HLS download (m3u8 → parse → download segments → merge → .mp4)
    // ========================================================================

    private fun downloadHls(
        context: android.content.Context,
        item: MediaItem,
        naming: SmartNaming.NameResult
    ): Long {
        val subDir = buildSubDir(item)
        val safeFilename = sanitizeFilename(naming.filename)

        val headers = buildHeaders(item)
        val db = AppDatabase.getInstance(context)

        // Insert record
        scope.launch {
            try {
                db.downloadDao().insert(
                    DownloadRecord(
                        downloadManagerId = -1L,
                        url = item.url, filename = safeFilename,
                        pageTitle = item.pageTitle, pageUrl = item.pageUrl,
                        mimeType = "video/mp4", mediaType = MediaType.HLS.name,
                        sizeBytes = item.sizeBytes,
                        seriesName = naming.seriesName, season = naming.season, episode = naming.episode,
                        state = "DOWNLOADING", startedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert HLS record: ${e.message}")
            }
            ensureService(context)
        }

        val throttler = DbThrottler(db, item.url, scope)

        HlsDownloader.download(
            context = context, m3u8Url = item.url,
            filename = safeFilename, subDir = subDir, headers = headers,
            callback = object : TurboCallback {
                override fun onProgress(progress: TurboProgress) {
                    try {
                        DownloadNotificationManager.showProgress(
                            url = item.url, title = safeFilename,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes,
                            speedBps = progress.speedBps, percent = progress.percent
                        )
                    } catch (_: Exception) {}
                    throttler.update(progress)
                }

                override fun onComplete(file: File) {
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().completeDownloadByUrl(
                                    url = item.url, state = "COMPLETED",
                                    ts = System.currentTimeMillis(), fileSize = file.length()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to complete HLS record: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "HLS error: ${error.message}", error)
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().updateStateByUrl(
                                    url = item.url, state = "FAILED", ts = System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update HLS error: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showError(item.url, safeFilename, error.message ?: "Erreur inconnue")
                    } catch (_: Exception) {}
                }
            }
        )
        return -1L
    }

    // ========================================================================
    // Direct download (mp4, mkv, webm, etc.) via TurboDownloadEngine
    // ========================================================================

    private fun downloadDirect(
        context: android.content.Context,
        item: MediaItem,
        naming: SmartNaming.NameResult
    ): Long {
        val subDir = buildSubDir(item, naming)
        val safeFilename = naming.filename
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destPath = "${downloadsDir.absolutePath}/$subDir/$safeFilename"

        val headers = buildHeaders(item)
        val db = AppDatabase.getInstance(context)

        // Insert record
        scope.launch {
            try {
                db.downloadDao().insert(
                    DownloadRecord(
                        downloadManagerId = -1L,
                        url = item.url, filename = safeFilename,
                        pageTitle = item.pageTitle, pageUrl = item.pageUrl,
                        mimeType = item.mimeType, mediaType = item.mediaType.name,
                        sizeBytes = item.sizeBytes,
                        seriesName = naming.seriesName, season = naming.season, episode = naming.episode,
                        state = "DOWNLOADING", startedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert direct record: ${e.message}")
            }
            ensureService(context)
        }

        val throttler = DbThrottler(db, item.url, scope)

        TurboDownloadEngine.download(
            context = context, url = item.url, destPath = destPath, headers = headers,
            callback = object : TurboCallback {
                override fun onProgress(progress: TurboProgress) {
                    try {
                        DownloadNotificationManager.showProgress(
                            url = item.url, title = safeFilename,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes,
                            speedBps = progress.speedBps, percent = progress.percent
                        )
                    } catch (_: Exception) {}
                    throttler.update(progress)
                }

                override fun onComplete(file: File) {
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().completeDownloadByUrl(
                                    url = item.url, state = "COMPLETED",
                                    ts = System.currentTimeMillis(), fileSize = file.length()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to complete direct record: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showComplete(item.url, safeFilename, file.length())
                        DownloadNotificationManager.dismiss(item.url)

                        val ext = safeFilename.substringAfterLast('.', "mp4")
                        val mimeMap = mapOf("mp4" to "video/mp4", "mkv" to "video/x-matroska",
                            "webm" to "video/webm", "mp3" to "audio/mpeg", "m4a" to "audio/mp4")
                        android.media.MediaScannerConnection.scanFile(
                            context, arrayOf(file.absolutePath),
                            arrayOf(mimeMap[ext] ?: "video/mp4"), null
                        )
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "Direct download error: ${error.message}", error)
                    try {
                        scope.launch {
                            try {
                                db.downloadDao().updateStateByUrl(
                                    url = item.url, state = "FAILED", ts = System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update direct error: ${e.message}")
                            }
                        }
                        DownloadNotificationManager.showError(item.url, safeFilename, error.message ?: "Erreur inconnue")
                    } catch (_: Exception) {}
                }
            }
        )
        return -1L
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buildSubDir(item: MediaItem, naming: SmartNaming.NameResult? = null): String {
        val base = if (item.mediaType == MediaType.AUDIO) "HITIF/Audio" else "HITIF/Video"
        if (naming == null) return base
        val seriesSub = naming.seriesName?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.take(60)
        return when {
            seriesSub != null && naming.season != null ->
                "$base/$seriesSub/Season_${naming.season.toString().padStart(2, '0')}"
            seriesSub != null -> "$base/$seriesSub"
            else -> base
        }
    }

    private fun buildHeaders(item: MediaItem): Map<String, String> = buildMap {
        // 1. Set Referer
        if (item.pageUrl.isNotBlank()) put("Referer", item.pageUrl)

        // 2. Extract cookies from WebView CookieManager
        try {
            val cookieManager = CookieManager.getInstance()
            val cookieUrls = mutableListOf<String>()

            if (item.pageUrl.isNotBlank()) {
                cookieUrls.add(item.pageUrl)
            }

            try {
                val mediaHost = URL(item.url).host ?: ""
                if (mediaHost.isNotBlank() && item.url != item.pageUrl.substringBefore('/')) {
                    cookieUrls.add("${URL(item.url).protocol}://$mediaHost/")
                }
            } catch (_: Exception) {}

            val cookieBuilder = StringBuilder()
            for (cookieUrl in cookieUrls) {
                val cookies = cookieManager.getCookie(cookieUrl)
                if (!cookies.isNullOrBlank()) {
                    if (cookieBuilder.isNotEmpty()) cookieBuilder.append("; ")
                    cookieBuilder.append(cookies)
                }
            }

            val allCookies = cookieBuilder.toString().trim()
            if (allCookies.isNotEmpty()) {
                put("Cookie", allCookies)
                Log.d(TAG, "Attached ${allCookies.length} chars of cookies for: ${item.url.substringBefore('?').take(80)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract cookies: ${e.message}")
        }

        // 3. Set User-Agent
        put("User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
    }

    private fun sanitizeFilename(name: String): String {
        return name.removeSuffix(".m3u8").removeSuffix(".mpd").removeSuffix(".m3u")
            .let { if (it.endsWith(".mp4")) it else "$it.mp4" }
    }

    // ========================================================================
    // Storage monitoring
    // ========================================================================

    /** Available storage in MB (external/storage downloads) */
    fun getAvailableStorageMB(context: android.content.Context): Long {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024.0 * 1024.0).toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available storage: ${e.message}")
            -1L
        }
    }

    /** Total storage in MB (external/storage downloads) */
    fun getTotalStorageMB(context: android.content.Context): Long {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(dir.absolutePath)
            stat.totalBytes / (1024.0 * 1024.0).toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get total storage: ${e.message}")
            -1L
        }
    }

    /** Throttles DB writes to at most once per second */
    private class DbThrottler(
        private val db: AppDatabase,
        private val url: String,
        private val scope: CoroutineScope
    ) {
        @Volatile private var lastUpdate = 0L

        fun update(progress: TurboProgress) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 1000L) {
                lastUpdate = now
                scope.launch {
                    try {
                        db.downloadDao().updateProgressByUrl(
                            url = url,
                            downloaded = progress.bytesDownloaded,
                            total = progress.totalBytes,
                            speed = progress.speedBps
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
