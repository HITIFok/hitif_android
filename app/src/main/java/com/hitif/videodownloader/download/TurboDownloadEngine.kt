package com.hitif.videodownloader.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Data & callback definitions
// ---------------------------------------------------------------------------

data class TurboProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val percent: Int,
    val isActive: Boolean
)

interface TurboCallback {
    fun onProgress(progress: TurboProgress)
    fun onComplete(file: File)
    fun onError(error: Throwable)
}

// ---------------------------------------------------------------------------
// TurboDownloadEngine – multi‑connection parallel downloader
// ---------------------------------------------------------------------------

object TurboDownloadEngine {

    private const val TAG = "TurboEngine"

    // ---------- configuration constants ----------
    private const val CHUNK_COUNT = 4
    private const val MULTI_CHUNK_THRESHOLD = 2L * 1024 * 1024   // 2 MB
    private const val SPEED_SAMPLE_INTERVAL_MS = 500L
    private const val CHUNK_RETRY_COUNT = 3          // retries per chunk before giving up
    private const val CHUNK_RETRY_BASE_DELAY_MS = 1500L   // base delay between retries

    // ---------- OkHttpClient with optimised settings ----------
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dns(FallbackDns())
            .retryOnConnectionFailure(true)
            .cookieJar(WebViewCookieJar())
            .build()
    }

    // ---------- active job registry ----------
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ---------- atomics used across chunks ----------
    private data class DownloadAtomics(
        val totalDownloaded: AtomicLong = AtomicLong(0L),
        val previousDownloaded: AtomicLong = AtomicLong(0L)
    )

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Start a turbo download.
     *
     * @param context   Android context used for cache‑dir temp files.
     * @param url       Remote URL to download.
     * @param destPath  Absolute path of the final destination file.
     * @param headers   Optional additional HTTP headers.
     * @param callback  Progress / completion / error callbacks.
     * @return          A [Job] that can be cancelled externally.
     */
    fun download(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String> = emptyMap(),
        callback: TurboCallback
    ): Job {
        // Cancel any previous download for the same URL
        activeJobs[url]?.cancel()

        // SupervisorJob + CoroutineExceptionHandler prevents ANY crash from propagating
        val handler = CoroutineExceptionHandler { _, throwable ->
            // Ignore cancellations — they happen when a new download replaces an old one
            if (throwable is CancellationException) {
                Log.d(TAG, "Job cancelled (replaced by new download): $url")
                return@CoroutineExceptionHandler
            }
            Log.e(TAG, "Uncaught error for $url: ${throwable.message}", throwable)
            try { callback.onError(throwable) } catch (_: Exception) {}
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val job = scope.launch {
            try {
                runDownload(context, url, destPath, headers, callback)
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $url")
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed: $url", e)
                try { callback.onError(e) } catch (_: Exception) {}
            } finally {
                activeJobs.remove(url)
            }
        }

        activeJobs[url] = job
        return job
    }

    /** Cancel every active download managed by this engine. */
    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    // =========================================================================
    // Internal implementation
    // =========================================================================

    private suspend fun runDownload(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String>,
        callback: TurboCallback
    ) = coroutineScope {

        // ----- Step 1: HEAD request for Content‑Length & Accept‑Ranges -----
        val contentLength: Long

        val headRequest = Request.Builder()
            .url(url)
            .head()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        contentLength = try {
            httpClient.newCall(headRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // HEAD not supported — fall back to single connection with unknown size
                    -1L
                } else {
                    resp.body?.contentLength() ?: -1L
                }
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "HEAD DNS failed, retrying with fallback: ${e.message}")
            delay(2000)
            try {
                httpClient.newCall(headRequest).execute().use { resp ->
                    if (!resp.isSuccessful) -1L else resp.body?.contentLength() ?: -1L
                }
            } catch (_: Exception) { -1L }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed, using single connection: ${e.message}")
            -1L
        }

        // Re‑check Accept‑Ranges with a small range probe (some servers omit it on HEAD)
        val supportsRange = if (contentLength > 0) probeRangeSupport(url, headers) else false

        if (contentLength <= 0L || !supportsRange) {
            // Unknown size or no range support – single connection
            singleChunkDownload(url, destPath, headers, null, callback)
            return@coroutineScope
        }

        // ----- Step 2: multi-chunk parallel download -----
        val atomics = DownloadAtomics()

        if (contentLength > MULTI_CHUNK_THRESHOLD && supportsRange) {
            multiChunkDownload(context, url, destPath, headers, contentLength, atomics, callback)
        } else {
            singleChunkDownload(url, destPath, headers, atomics, callback)
        }
    }

    // -----------------------------------------------------------------------
    // Probe whether the server actually supports byte ranges
    // -----------------------------------------------------------------------

    private suspend fun probeRangeSupport(url: String, headers: Map<String, String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val probe = Request.Builder()
                    .url(url)
                    .head()
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .addHeader("Range", "bytes=0-0")
                    .build()

                httpClient.newCall(probe).execute().use { resp ->
                    resp.isSuccessful || resp.code == 206
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Single‑chunk (fallback) download — FULLY crash-safe
    // -----------------------------------------------------------------------

    private suspend fun singleChunkDownload(
        url: String,
        destPath: String,
        headers: Map<String, String>,
        atomics: DownloadAtomics?,
        callback: TurboCallback
    ) {
        var lastError: Throwable? = null

        for (attempt in 0 until CHUNK_RETRY_COUNT) {
            try {
                if (attempt > 0) {
                    val delayMs = CHUNK_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                    Log.w(TAG, "singleChunkDownload attempt $attempt failed, retrying in ${delayMs}ms...")
                    delay(delayMs)
                }

                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Download failed: ${response.code} ${response.message}")
                        }

                        val body = response.body
                            ?: throw IllegalStateException("Response body is null")

                        val totalBytes = body.contentLength()
                        val destFile = File(destPath)
                        destFile.parentFile?.mkdirs()

                        var downloaded = 0L
                        val prevRef = AtomicLong(0L)
                        var lastSpeedTime = System.currentTimeMillis()

                        body.byteStream().use { input ->
                            FileOutputStream(destFile).use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    ensureActive()
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    atomics?.totalDownloaded?.addAndGet(read.toLong())

                                    val now = System.currentTimeMillis()
                                    if (now - lastSpeedTime >= SPEED_SAMPLE_INTERVAL_MS) {
                                        val delta = downloaded - prevRef.getAndSet(downloaded)
                                        val elapsed = (now - lastSpeedTime) / 1000.0
                                        val speed = if (elapsed > 0) (delta / elapsed).toLong() else 0L
                                        val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0

                                        try {
                                            callback.onProgress(
                                                TurboProgress(
                                                    bytesDownloaded = downloaded,
                                                    totalBytes = totalBytes,
                                                    speedBps = speed,
                                                    percent = percent,
                                                    isActive = true
                                                )
                                            )
                                        } catch (_: Exception) {}
                                        lastSpeedTime = now
                                    }
                                }
                            }
                        }

                        try {
                            callback.onProgress(
                                TurboProgress(downloaded, totalBytes, 0L, 100, false)
                            )
                            callback.onComplete(destFile)
                        } catch (_: Exception) {}
                    }
                }
                return  // success
            } catch (e: CancellationException) {
                throw e  // don't retry on cancellation
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "singleChunkDownload attempt ${attempt + 1}/$CHUNK_RETRY_COUNT failed: ${e.message}")
            }
        }
        // All retries exhausted
        Log.e(TAG, "singleChunkDownload all $CHUNK_RETRY_COUNT retries failed")
        try { callback.onError(lastError ?: IllegalStateException("Download failed after retries")) } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------
    // Multi‑chunk parallel download — FULLY crash-safe
    // -----------------------------------------------------------------------

    private suspend fun multiChunkDownload(
        context: Context,
        url: String,
        destPath: String,
        headers: Map<String, String>,
        contentLength: Long,
        atomics: DownloadAtomics,
        callback: TurboCallback
    ) = coroutineScope {

        val chunkSize = contentLength / CHUNK_COUNT
        val tempFiles = mutableListOf<File>()

        // Speed‑tracking coroutine
        val speedJob = launch(Dispatchers.IO) {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(SPEED_SAMPLE_INTERVAL_MS)
                val nowBytes = atomics.totalDownloaded.get()
                val delta = nowBytes - lastBytes
                lastBytes = nowBytes
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime) / 1000.0
                lastTime = now
                val speed = if (elapsed > 0) (delta / elapsed).toLong() else 0L
                val percent = ((nowBytes * 100) / contentLength).toInt().coerceAtMost(100)

                try {
                    callback.onProgress(
                        TurboProgress(
                            bytesDownloaded = nowBytes,
                            totalBytes = contentLength,
                            speedBps = speed,
                            percent = percent,
                            isActive = true
                        )
                    )
                } catch (_: Exception) {}
            }
        }

        try {
            // Launch concurrent chunk downloads
            val chunkDeferreds = (0 until CHUNK_COUNT).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    val end = if (index == CHUNK_COUNT - 1) contentLength - 1 else start + chunkSize - 1

                    val tempFile = File(context.cacheDir, "turbo_${url.hashCode()}_chunk_$index.tmp")
                    tempFiles.add(tempFile)
                    downloadChunk(url, headers, start, end, tempFile, atomics)
                }
            }

            // Await all chunks
            chunkDeferreds.awaitAll()

            // Merge temp files into the final destination
            val destFile = File(destPath)
            destFile.parentFile?.mkdirs()
            mergeFiles(tempFiles, destFile)

            // Report completion
            speedJob.cancel()
            try {
                callback.onProgress(
                    TurboProgress(contentLength, contentLength, 0L, 100, false)
                )
                callback.onComplete(destFile)
            } catch (_: Exception) {}

        } catch (t: Throwable) {
            speedJob.cancel()
            cleanupTempFiles(tempFiles)
            try { callback.onError(t) } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Download a single byte‑range chunk — crash-safe with retry logic
    // -----------------------------------------------------------------------

    private suspend fun downloadChunk(
        url: String,
        headers: Map<String, String>,
        startByte: Long,
        endByte: Long,
        tempFile: File,
        atomics: DownloadAtomics
    ) {
        var lastError: Exception? = null

        for (attempt in 0 until CHUNK_RETRY_COUNT) {
            try {
                // If retrying, delete the partial temp file and start fresh
                if (attempt > 0 && tempFile.exists()) {
                    tempFile.delete()
                    val delayMs = CHUNK_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                    Log.w(TAG, "Chunk $startByte-$endByte failed (attempt $attempt), retrying in ${delayMs}ms...")
                    delay(delayMs)
                }

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .addHeader("Range", "bytes=$startByte-$endByte")
                    .build()

                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code != 206 && response.code != 200) {
                            throw IllegalStateException(
                                "Chunk download failed for range $startByte-$endByte: ${response.code} ${response.message}"
                            )
                        }

                        val body = response.body
                            ?: throw IllegalStateException("Chunk response body is null for range $startByte-$endByte")

                        body.byteStream().use { input ->
                            tempFile.parentFile?.mkdirs()
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    atomics.totalDownloaded.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                }
                // Success — return
                return
            } catch (e: CancellationException) {
                throw e  // don't retry on cancellation
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "downloadChunk attempt ${attempt + 1}/$CHUNK_RETRY_COUNT failed for range $startByte-$endByte: ${e.message}")
            }
        }
        // All retries exhausted
        Log.e(TAG, "downloadChunk all $CHUNK_RETRY_COUNT retries failed for range $startByte-$endByte")
        throw lastError ?: IllegalStateException("Chunk download failed after $CHUNK_RETRY_COUNT retries")
    }

    // -----------------------------------------------------------------------
    // Merge ordered temp files into the destination file
    // -----------------------------------------------------------------------

    private fun mergeFiles(sourceFiles: List<File>, destFile: File) {
        FileOutputStream(destFile).use { output ->
            sourceFiles.forEach { file ->
                if (!file.exists()) return@forEach
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup helper – delete temp files (called on error / cancel)
    // -----------------------------------------------------------------------

    private fun cleanupTempFiles(files: List<File>) {
        files.forEach { file ->
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }
}
