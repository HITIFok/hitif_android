package com.hitif.videodownloader.network

import android.webkit.WebResourceRequest
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.model.MediaQuality
import com.hitif.videodownloader.util.SmartNamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyses intercepted WebView requests and sniffs media URLs.
 * Mirrors the logic of the HITIF Chrome extension's content script.
 */
class MediaDetector(
    private val onMediaFound: (MediaItem) -> Unit
) {
    // Deduplicate by normalised URL
    private val seen = ConcurrentHashMap<String, Boolean>()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    // ── URL pattern lists (mirrors extension's heuristics) ──────────────────

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "webm", "mkv", "avi", "mov", "flv", "m4v",
        "3gp", "ts", "mts", "m2ts", "vob", "ogv"
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"
    )
    private val STREAM_EXTENSIONS = mapOf(
        "m3u8" to MediaType.HLS,
        "m3u"  to MediaType.HLS,
        "mpd"  to MediaType.DASH
    )

    private val VIDEO_MIME_PREFIXES = listOf("video/", "application/x-mpegurl",
        "application/vnd.apple.mpegurl", "application/dash+xml")
    private val AUDIO_MIME_PREFIXES = listOf("audio/")

    private val BLOCKED_HOSTS = setOf(
        "googlevideo.com", "googleads.g.doubleclick.net", "doubleclick.net",
        "ads.youtube.com", "static.ads-twitter.com"
    )
    private val SKIP_PATTERNS = listOf(
        Regex("manifest\\.json$"), Regex("thumbnail"), Regex("poster"),
        Regex("preview"), Regex("storyboard"), Regex("/ad/"), Regex("/ads/"),
        Regex("beacon"), Regex("analytics"), Regex("tracking")
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Called from WebViewClient.shouldInterceptRequest — runs on a background thread.
     */
    fun analyse(request: WebResourceRequest, pageUrl: String, pageTitle: String) {
        val url = request.url.toString()
        analyseUrl(url, request.requestHeaders ?: emptyMap(), pageUrl, pageTitle)
    }

    /**
     * Analyse a raw URL string (e.g. from JS bridge).
     */
    fun analyseUrl(url: String, headers: Map<String, String> = emptyMap(),
                   pageUrl: String = "", pageTitle: String = "") {
        val clean = url.substringBefore('?').substringBefore('#').lowercase().trim()
        val key   = url.substringBefore('?').substringBefore('#')
        if (seen.putIfAbsent(key, true) != null) return   // already processed

        // Quick path: skip obviously irrelevant
        if (shouldSkip(clean)) return

        val ext = clean.substringAfterLast('.', "")

        when {
            ext in STREAM_EXTENSIONS -> {
                emit(buildItem(url, ext, STREAM_EXTENSIONS[ext]!!, headers, -1L, pageUrl, pageTitle))
            }
            ext in VIDEO_EXTENSIONS -> {
                scope.launch { sniffAndEmit(url, headers, MediaType.VIDEO, pageUrl, pageTitle) }
            }
            ext in AUDIO_EXTENSIONS -> {
                scope.launch { sniffAndEmit(url, headers, MediaType.AUDIO, pageUrl, pageTitle) }
            }
            else -> {
                // HEAD-sniff only if URL looks interesting (no extension or cdn path)
                if (looksLikeMedia(url)) {
                    scope.launch { sniffAndEmit(url, headers, MediaType.UNKNOWN, pageUrl, pageTitle) }
                }
            }
        }
    }

    fun reset() = seen.clear()

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun shouldSkip(cleanUrl: String): Boolean {
        BLOCKED_HOSTS.forEach { host -> if (cleanUrl.contains(host)) return true }
        SKIP_PATTERNS.forEach { re -> if (re.containsMatchIn(cleanUrl)) return true }
        return false
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/video/") || lower.contains("/audio/") ||
               lower.contains("/media/") || lower.contains("/stream/") ||
               lower.contains("/hls/")  || lower.contains("/dash/") ||
               lower.contains("blob:")  || lower.contains("cdn") ||
               lower.contains("chunked") || lower.contains("segment")
    }

    private suspend fun sniffAndEmit(
        url: String, headers: Map<String, String>,
        hintType: MediaType, pageUrl: String, pageTitle: String
    ) {
        try {
            val req = Request.Builder().url(url).method("HEAD", null).apply {
                headers.forEach { (k, v) ->
                    if (k.lowercase() !in listOf("host", "content-length")) addHeader(k, v)
                }
                header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            }.build()

            val resp = http.newCall(req).execute()
            val mime = resp.header("Content-Type", "") ?: ""
            val size = resp.header("Content-Length", "-1")?.toLongOrNull() ?: -1L
            resp.close()

            val resolvedType = when {
                VIDEO_MIME_PREFIXES.any { mime.startsWith(it) } -> {
                    if (mime.contains("mpegurl") || mime.contains("dash")) MediaType.HLS
                    else MediaType.VIDEO
                }
                AUDIO_MIME_PREFIXES.any { mime.startsWith(it) } -> MediaType.AUDIO
                hintType != MediaType.UNKNOWN -> hintType
                else -> return   // not media
            }

            emit(buildItem(url, mime.substringAfter('/').substringBefore(';'),
                resolvedType, headers, size, pageUrl, pageTitle))
        } catch (_: Exception) {
            // Network error — if hint says VIDEO/AUDIO, still emit
            if (hintType == MediaType.VIDEO || hintType == MediaType.AUDIO) {
                emit(buildItem(url, "", hintType, headers, -1L, pageUrl, pageTitle))
            }
        }
    }

    private fun buildItem(
        url: String, extOrMime: String, type: MediaType,
        headers: Map<String, String>, size: Long,
        pageUrl: String, pageTitle: String
    ): MediaItem {
        // Smart naming: try to generate a clean name from the page URL
        val smartName = if (pageUrl.isNotBlank()) {
            SmartNamer.smartNameForPage(pageUrl, pageTitle)
        } else {
            ""
        }
        val rawName = url.substringBefore('?').substringAfterLast('/')
        val safeName = if (smartName.isNotBlank() && smartName.length > 3) {
            // Use smart name with appropriate extension
            val ext = if (extOrMime.contains('/')) extOrMime.substringAfter('/') else extOrMime
            val cleanExt = ext.take(4).ifBlank { "mp4" }
            "$smartName.$cleanExt"
        } else {
            rawName.ifBlank { "media_${System.currentTimeMillis()}" }
                .let { if (!it.contains('.')) "$it.${extOrMime.take(4)}" else it }
        }

        val quality = guessQuality(url, headers)

        return MediaItem(
            url        = url,
            filename   = safeName,
            mimeType   = extOrMime,
            mediaType  = type,
            quality    = quality,
            sizeBytes  = size,
            pageUrl    = pageUrl,
            pageTitle  = pageTitle
        )
    }

    private fun guessQuality(url: String, headers: Map<String, String>): MediaQuality {
        val lower = url.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> MediaQuality.ULTRA
            lower.contains("1080") || lower.contains("fhd") -> MediaQuality.HIGH
            lower.contains("720")  || lower.contains("hd")  -> MediaQuality.MEDIUM
            lower.contains("480")  || lower.contains("360") -> MediaQuality.LOW
            else -> MediaQuality.UNKNOWN
        }
    }

    private fun emit(item: MediaItem) {
        onMediaFound(item)
    }
}
