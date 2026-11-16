package com.videob.vb_google

import java.io.BufferedInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.regex.Pattern
import android.util.Base64
import android.util.Log
import okhttp3.Request
import org.json.JSONObject

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
    private val encodedConfigPattern =
        Pattern.compile(
            """window\._econfig\s*=\s*["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        )

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

    fun downloadText(sourceUrl: String, useDoh: Boolean = false): String =
        download(sourceUrl, useDoh, sourceUrl)

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
        val embedHtml = download(iframeUrl, useDoh, sourceUrl, fetchDest = "iframe")
        extractFirstStreamUrl(embedHtml)?.let { streamUrl ->
            Log.d(tag, "resolve stream found on embed stream=$streamUrl referer=$iframeUrl")
            return ResolvedStream(
                streamUrl = streamUrl,
                refererUrl = iframeUrl,
            )
        }

        extractEncodedStreamUrl(embedHtml)?.let { streamUrl ->
            Log.d(tag, "resolve encoded stream found on embed stream=$streamUrl referer=$iframeUrl")
            return ResolvedStream(
                streamUrl = streamUrl,
                refererUrl = iframeUrl,
            )
        }

        Log.d(tag, "resolve source-page fallback source=$sourceUrl iframe=$iframeUrl")
        return ResolvedStream(
            streamUrl = sourceUrl,
            refererUrl = sourceUrl,
            isEmbed = true,
        )
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

    /**
     * The current embedded player stores its stream URL in window._econfig.
     * This mirrors the decoder shipped by the provider in assets/stream.js.
     */
    private fun extractEncodedStreamUrl(html: String): String? {
        val matcher = encodedConfigPattern.matcher(html)
        if (!matcher.find()) {
            return null
        }

        return runCatching {
            val encoded = matcher.group(1) ?: return@runCatching null
            val packed = String(
                Base64.decode(encoded, Base64.DEFAULT),
                StandardCharsets.ISO_8859_1,
            )
            if (packed.isEmpty()) {
                return@runCatching null
            }

            val chunkLength = (packed.length + 3) / 4
            val order = intArrayOf(2, 0, 3, 1)
            val parts = arrayOfNulls<String>(4)
            var offset = 0

            repeat(4) { index ->
                val end = minOf(offset + chunkLength, packed.length)
                if (offset >= end) {
                    return@runCatching null
                }
                val chunk = packed.substring(offset, end)
                offset = end
                if (chunk.length < 4) {
                    return@runCatching null
                }

                val cleaned = chunk.substring(0, 3) + chunk.substring(4)
                parts[order[index]] = String(
                    Base64.decode(cleaned, Base64.DEFAULT),
                    StandardCharsets.ISO_8859_1,
                )
            }

            if (parts.any { it == null }) {
                return@runCatching null
            }
            val joined = parts.filterNotNull().joinToString("")
            val jsonText = String(
                Base64.decode(joined, Base64.DEFAULT),
                StandardCharsets.UTF_8,
            )
            val config = JSONObject(jsonText)

            listOf("stream_url", "stream_url_nop2p", "url_nop2p")
                .asSequence()
                .map { key -> config.optString(key).trim() }
                .firstOrNull { candidate ->
                    candidate.startsWith("http://") || candidate.startsWith("https://")
                }
        }.onFailure { error ->
            Log.w(tag, "encoded stream decode failed", error)
        }.getOrNull()
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

    private fun download(
        url: String,
        useDoh: Boolean,
        refererUrl: String,
        fetchDest: String = "document",
    ): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            )
            .header("Referer", refererUrl)
            // Il server player (embed /e/<id>) risponde "Not allowed" alle
            // richieste prive di intestazioni Sec-Fetch: vanno sempre inviate.
            .header("Sec-Fetch-Site", "cross-site")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", fetchDest)
            .header("Sec-Fetch-User", "?1")
            .build()

        NetworkClientFactory.get(useDoh).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = if (response.code == 403) {
                    "Il server video ha rifiutato la richiesta (403)"
                } else {
                    "Errore download sorgente: ${response.code}"
                }
                error(reason)
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
    val isEmbed: Boolean = false,
)
