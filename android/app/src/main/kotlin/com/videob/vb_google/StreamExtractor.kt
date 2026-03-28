package com.videob.vb_google

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.LinkedHashSet
import java.util.regex.Pattern

object StreamExtractor {
    private val iframePattern =
        Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
    private val mediaPattern =
        Pattern.compile(
            """https?:\/\/[^"'\\\s)]+(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^"'\\\s)]*)?""",
            Pattern.CASE_INSENSITIVE,
        )
    private val urlPattern =
        Pattern.compile("""https?:\/\/[^"'\\\s<>()]+""", Pattern.CASE_INSENSITIVE)

    fun extractLinks(sourceUrl: String): List<String> {
        val html = download(sourceUrl)
        val links = LinkedHashSet<String>()

        collectMatches(iframePattern, html, sourceUrl, links)
        collectMatches(mediaPattern, html, sourceUrl, links)
        collectMatches(urlPattern, html, sourceUrl, links)

        if (looksLikeDirectMedia(sourceUrl)) {
            links.add(sourceUrl)
        }

        return links
            .filterNot { it.contains("doubleclick") || it.contains("googlesyndication") }
            .take(12)
    }

    private fun collectMatches(
        pattern: Pattern,
        html: String,
        sourceUrl: String,
        links: LinkedHashSet<String>,
    ) {
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val candidate = matcher.group(1) ?: matcher.group()
            val normalized = normalizeUrl(sourceUrl, candidate)
            if (normalized != null && isUseful(normalized)) {
                links.add(normalized)
            }
        }
    }

    private fun isUseful(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun normalizeUrl(base: String, candidate: String): String? {
        val cleaned = candidate.trim().removePrefix("&quot;").removeSuffix("&quot;")
        if (cleaned.isBlank()) {
            return null
        }

        return when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            else -> {
                try {
                    URI(base).resolve(cleaned).toString()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun looksLikeDirectMedia(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains(".m3u8") ||
            normalized.contains(".mpd") ||
            normalized.contains(".mp4") ||
            normalized.contains(".webm") ||
            normalized.contains(".mkv")
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            )
            setRequestProperty("Referer", "https://sportsonline.si/")
        }

        return connection.inputStream.use { input ->
            BufferedInputStream(input).reader(Charsets.UTF_8).readText()
        }
    }
}
