package com.videob.vb_google

import java.io.BufferedInputStream
import java.net.URI
import java.util.LinkedHashSet
import java.util.regex.Pattern
import android.util.Log
import okhttp3.Request

object StreamExtractor {
    private const val tag = "VideoBResolve"
    private val iframePattern =
        Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
    private val streamPattern =
        Pattern.compile(
            """(?:["']?(?:file|src|source|hlsUrl|streamUrl)["']?\s*[:=]\s*|var\s+src\s*=\s*)["']?(https?:\/\/[^"'>\s]+(?:\.m3u8|\.mp4)[^"'>\s]*)""",
            Pattern.CASE_INSENSITIVE,
        )
    private val mediaPattern =
        Pattern.compile(
            """https?:\/\/[^"'\\\s)]+(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^"'\\\s)]*)?""",
            Pattern.CASE_INSENSITIVE,
        )
    private val urlPattern =
        Pattern.compile("""https?:\/\/[^"'\\\s<>()]+""", Pattern.CASE_INSENSITIVE)

    fun extractLinks(sourceUrl: String, useDoh: Boolean = false): List<String> {
        val html = download(sourceUrl, useDoh, sourceUrl)
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

    fun resolveStream(sourceUrl: String, useDoh: Boolean = false): ResolvedStream? {
        Log.d(tag, "resolve start source=$sourceUrl doh=$useDoh")
        if (looksLikeDirectMedia(sourceUrl)) {
            Log.d(tag, "resolve direct media source=$sourceUrl")
            return ResolvedStream(
                streamUrl = sourceUrl,
                refererUrl = sourceUrl,
            )
        }

        val html = download(sourceUrl, useDoh, sourceUrl)
        extractFirstStreamUrl(html)?.let { streamUrl ->
            Log.d(tag, "resolve stream found on source page stream=$streamUrl referer=$sourceUrl")
            return ResolvedStream(
                streamUrl = streamUrl,
                refererUrl = sourceUrl,
            )
        }

        val iframeUrl = extractFirstIframeUrl(html, sourceUrl) ?: return null
        Log.d(tag, "resolve iframe found iframe=$iframeUrl source=$sourceUrl")
        val embedHtml = download(iframeUrl, useDoh, sourceUrl)
        extractFirstStreamUrl(embedHtml)?.let { streamUrl ->
            Log.d(tag, "resolve stream found on embed stream=$streamUrl referer=$iframeUrl")
            return ResolvedStream(
                streamUrl = streamUrl,
                refererUrl = iframeUrl,
            )
        }

        Log.d(tag, "resolve failed source=$sourceUrl iframe=$iframeUrl")
        return null
    }

    private fun extractFirstStreamUrl(html: String): String? {
        val matcher = streamPattern.matcher(html)
        while (matcher.find()) {
            val candidate = matcher.group(1) ?: continue
            if (isUseful(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun extractFirstIframeUrl(html: String, sourceUrl: String): String? {
        val matcher = iframePattern.matcher(html)
        while (matcher.find()) {
            val candidate = matcher.group(1) ?: continue
            val normalized = normalizeUrl(sourceUrl, candidate)
            if (normalized != null && isUseful(normalized)) {
                return normalized
            }
        }
        return null
    }

    private fun download(url: String, useDoh: Boolean, refererUrl: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            )
            .header("Referer", refererUrl)
            .build()

        NetworkClientFactory.get(useDoh).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Errore download sorgente: ${response.code}")
            }
            val body = response.body ?: error("Risposta vuota dalla sorgente.")
            return body.byteStream().use { input ->
                BufferedInputStream(input).reader(Charsets.UTF_8).readText()
            }
        }
    }
}

data class ResolvedStream(
    val streamUrl: String,
    val refererUrl: String,
)
