package com.hitif.videodownloader.util

import java.net.URL

/**
 * Smart Naming utility - ported from the Chrome extension's season_panel.js spSmartName().
 *
 * Extracts anime/series name from episode URLs and generates clean filenames.
 *
 * Example:
 *   "https://ww.animesultra.org/anime-vf/2701-jujutsu-kaisen-saison-3-vf-au/episode-5.html"
 *   -> animeName="Jujutsu Kaisen Saison 3", basename="Jujutsu Kaisen Saison 3 episode 5"
 */
object SmartNamer {

    private val STRIP_SUFFIXES = listOf(
        "vostfr", "vfra", "vfi", "vost", "vf", "va", "au", "fr",
        "sub", "dub", "hd", "streaming", "french", "eng", "multi"
    )

    private val SKIP_SEGMENTS = setOf(
        "anime-vf", "anime-vostfr", "anime-vost", "anime", "voir-series",
        "voir-anime", "voir", "series", "vf", "vostfr", "streaming", "video", "episodes"
    )

    private val GENRE_PREFIXES = listOf(
        "drame", "action", "comedie", "romance", "fantastique", "thriller",
        "horreur", "shonen", "seinen", "shojo", "isekai", "sport", "aventure",
        "historique", "policier"
    )

    private val SMALL_WORDS = setOf(
        "de", "du", "la", "le", "les", "un", "une", "des", "et", "ou",
        "en", "au", "aux", "ta", "ton", "sa", "son", "ma", "mon", "sur",
        "sous", "par", "pour", "avec", "sans", "dans"
    )

    data class NamingResult(
        val animeName: String,
        val seasonNum: Int?,
        val episodeNum: Int,
        val basename: String
    )

    /**
     * Extract smart name from a URL pattern with episode number placeholder {N}.
     */
    fun smartName(patternUrl: String, epNum: Int): NamingResult {
        try {
            val cleanUrl = patternUrl.replace("{N}", epNum.toString())
            val u = URL(cleanUrl)
            val parts = u.path.split("/").filter { it.isNotBlank() }

            var animeName = ""
            var seasonNum: Int? = null
            val episode = epNum

            for (part in parts) {
                val lower = part.lowercase()

                val epMatch = Regex("""^episode-(\d+)(?:\.html?)?$""").find(lower)
                    ?: Regex("""^(\d+)-episode(?:\.html?)?$""").find(lower)
                if (epMatch != null) continue

                val saisonMatch = Regex("""^(\d+)-saison$""").find(lower)
                    ?: Regex("""^saison-(\d+)$""").find(lower)
                if (saisonMatch != null) {
                    seasonNum = saisonMatch.groupValues[1].toIntOrNull()
                    continue
                }

                if (lower in SKIP_SEGMENTS) continue
                if (GENRE_PREFIXES.any { lower.startsWith(it) }) continue
                if (Regex("""^[a-z]+-s$""").matches(lower)) continue
                if (part.contains("{N}", ignoreCase = true)) continue
                if (Regex("""^\d+$""").matches(lower)) continue

                val hasId = Regex("""^\d+-""").matches(part)
                var slug = part.replace(Regex("""^\d+[-_]"""), "")

                var prevSlug = ""
                while (slug != prevSlug) {
                    prevSlug = slug
                    val regex = STRIP_SUFFIXES.joinToString("|") { Regex.escape(it) }
                    slug = slug.replace(Regex("""[-_]($regex)$""", RegexOption.IGNORE_CASE), "")
                }

                if (slug.isBlank()) continue

                val words = slug.split(Regex("""[-_]+"""))
                val titled = words.mapIndexed { index, word ->
                    if (index == 0 || !SMALL_WORDS.contains(word.lowercase())) {
                        // Capitalize first letter, lowercase the rest
                        word.replaceFirstChar { it.uppercase() }.let {
                            if (it.length > 1) it.substring(0, 1) + it.substring(1).lowercase()
                            else it.uppercase()
                        }
                    } else {
                        word.lowercase()
                    }
                }
                val candidate = titled.joinToString(" ").trim()

                if (candidate.isNotBlank() && (hasId || animeName.isBlank())) {
                    animeName = candidate
                }
            }

            val name = animeName
                .replace(Regex("""\.(?:html?|php|asp)$""", RegexOption.IGNORE_CASE), "")
                .trim()

            val base = if (name.isNotBlank()) {
                if (seasonNum != null) "$name Saison $seasonNum episode $episode"
                else "$name episode $episode"
            } else {
                "Episode ${episode.toString().padStart(2, '0')}"
            }

            return NamingResult(
                animeName = name,
                seasonNum = seasonNum,
                episodeNum = episode,
                basename = base
            )
        } catch (e: Exception) {
            return NamingResult("", null, epNum, "Episode ${epNum.toString().padStart(2, '0')}")
        }
    }

    /**
     * Auto-detect URL pattern by replacing the episode number with {N}.
     *
     *   ".../episode-5.html" -> ".../episode-{N}.html"
     *   ".../5-episode.html" -> ".../{N}-episode.html"
     */
    fun detectPattern(rawUrl: String): String {
        if (!rawUrl.startsWith("http")) return rawUrl
        try {
            val u = URL(rawUrl)
            val parts = u.path.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) return rawUrl
            val last = parts.last()

            var p = last.replace(Regex("""^(episode-)(\d+)(\.html?)$""", RegexOption.IGNORE_CASE), "$1{N}$3")
            if (p != last) return buildPatternUrl(u, parts, p)

            p = last.replace(Regex("""^(\d+)(-episode\.html?)$""", RegexOption.IGNORE_CASE), "{N}$2")
            if (p != last) return buildPatternUrl(u, parts, p)

            val m = Regex("""(\d+)""").find(last)
            if (m != null) {
                p = last.replace(m.groupValues[1], "{N}")
                return buildPatternUrl(u, parts, p)
            }
        } catch (_: Exception) {}
        return rawUrl
    }

    private fun buildPatternUrl(u: URL, parts: List<String>, newLast: String): String {
        return "${u.protocol}://${u.host}/${parts.dropLast(1).joinToString("/")}/$newLast"
    }

    /**
     * Smart naming for single media items from any page URL.
     */
    fun smartNameForPage(pageUrl: String, pageTitle: String): String {
        val result = smartName(detectPattern(pageUrl), 1)
        if (result.animeName.isNotBlank()) {
            return result.animeName
        }
        return pageTitle
            .replace(Regex("""[^\p{L}\p{N}\p{M}\-\s_.]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(190)
            .ifBlank { "video" }
    }
}
