package com.videob.vb_google

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

object LocalProxyServer {
    private const val tag = "VideoBProxy"
    private const val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val referer = "https://sportsonline.si/"
    private const val origin = "https://sportsonline.si"

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    @Synchronized
    fun proxyUrl(targetUrl: String): String {
        val socket = ensureStarted()
        val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
        val host = resolveHostAddress()
        val proxyUrl = "http://$host:${socket.localPort}/proxy?url=$encoded"
        Log.d(tag, "Proxy URL generated for target=$targetUrl via=$proxyUrl")
        return proxyUrl
    }

    private fun ensureStarted(): ServerSocket {
        serverSocket?.let { return it }

        val socket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        Log.d(tag, "Local proxy started on port=${socket.localPort}")

        acceptExecutor.execute {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    clientExecutor.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (socket.isClosed) {
                        return@execute
                    }
                }
            }
        }

        return socket
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                Log.d(tag, "Incoming request: $requestLine")
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeError(socket.getOutputStream(), 400, "Bad Request")
                    return
                }

                val method = parts[0].uppercase(Locale.ROOT)
                val path = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                        val value = line.substring(separator + 1).trim()
                        headers[name] = value
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    writeError(socket.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                if (!path.startsWith("/proxy?")) {
                    writeError(socket.getOutputStream(), 404, "Not Found")
                    return
                }

                val targetUrl = parseTargetUrl(path)
                if (targetUrl.isNullOrBlank()) {
                    writeError(socket.getOutputStream(), 400, "Missing URL")
                    return
                }

                proxyRequest(
                    output = socket.getOutputStream(),
                    targetUrl = targetUrl,
                    requestHeaders = headers,
                    headOnly = method == "HEAD",
                )
            } catch (error: Exception) {
                Log.e(tag, "Proxy request failed", error)
                try {
                    writeError(socket.getOutputStream(), 502, "Proxy Error")
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun parseTargetUrl(path: String): String? {
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return null

        return query.split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }

    private fun proxyRequest(
        output: OutputStream,
        targetUrl: String,
        requestHeaders: Map<String, String>,
        headOnly: Boolean,
    ) {
        val upstream = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            requestMethod = if (headOnly) "HEAD" else "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", referer)
            setRequestProperty("Origin", origin)
            requestHeaders["range"]?.let { setRequestProperty("Range", it) }
            requestHeaders["accept"]?.let { setRequestProperty("Accept", it) }
            requestHeaders["accept-encoding"]?.let { setRequestProperty("Accept-Encoding", it) }
            requestHeaders["connection"]?.let { setRequestProperty("Connection", it) }
        }

        val statusCode = upstream.responseCode
        val contentType = upstream.contentType ?: guessContentType(targetUrl)
        val contentRange = upstream.getHeaderField("Content-Range")
        val acceptRanges = upstream.getHeaderField("Accept-Ranges")
        val bodyStream = if (statusCode >= 400) {
            upstream.errorStream
        } else {
            upstream.inputStream
        }
        Log.d(
            tag,
            "Upstream response target=$targetUrl status=$statusCode type=$contentType range=${requestHeaders["range"]}",
        )

        if (bodyStream == null) {
            writeResponse(
                output = output,
                statusCode = statusCode,
                contentType = contentType,
                contentLength = 0,
                contentRange = contentRange,
                acceptRanges = acceptRanges,
                body = null,
                headOnly = headOnly,
            )
            return
        }

        bodyStream.use { stream ->
            if (!headOnly && isPlaylist(targetUrl, contentType)) {
                val playlistText = BufferedInputStream(stream).reader(StandardCharsets.UTF_8).readText()
                val rewritten = rewritePlaylist(targetUrl, playlistText)
                val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                writeResponse(
                    output = output,
                    statusCode = statusCode,
                    contentType = "application/vnd.apple.mpegurl",
                    contentLength = bytes.size.toLong(),
                    contentRange = null,
                    acceptRanges = "bytes",
                    body = ByteArrayInputStream(bytes),
                    headOnly = false,
                )
            } else {
                writeResponse(
                    output = output,
                    statusCode = statusCode,
                    contentType = contentType,
                    contentLength = upstream.contentLengthLong,
                    contentRange = contentRange,
                    acceptRanges = acceptRanges,
                    body = stream,
                    headOnly = headOnly,
                )
            }
        }
    }

    private fun rewritePlaylist(baseUrl: String, playlistText: String): String {
        val resolvedBase = URI(baseUrl)
        return playlistText
            .lineSequence()
            .map { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> line
                    else -> {
                        val absolute = resolvedBase.resolve(trimmed).toString()
                        proxyUrl(absolute)
                    }
                }
            }
            .joinToString("\n")
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerType = contentType.lowercase(Locale.ROOT)
        return lowerUrl.contains(".m3u8") ||
            lowerType.contains("mpegurl") ||
            lowerType.contains("vnd.apple.mpegurl")
    }

    private fun guessContentType(url: String): String =
        when {
            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            url.contains(".ts", ignoreCase = true) -> "video/mp2t"
            url.contains(".mp4", ignoreCase = true) -> "video/mp4"
            url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> "application/octet-stream"
        }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        contentType: String,
        contentLength: Long,
        contentRange: String?,
        acceptRanges: String?,
        body: InputStream?,
        headOnly: Boolean,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            502 -> "Bad Gateway"
            else -> "OK"
        }

        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Type: $contentType\r\n")
            if (contentLength >= 0) {
                append("Content-Length: $contentLength\r\n")
            }
            if (!acceptRanges.isNullOrBlank()) {
                append("Accept-Ranges: $acceptRanges\r\n")
            } else if (statusCode == 206) {
                append("Accept-Ranges: bytes\r\n")
            }
            if (!contentRange.isNullOrBlank()) {
                append("Content-Range: $contentRange\r\n")
            }
            append("\r\n")
        }

        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        if (!headOnly && body != null) {
            body.copyTo(output)
        }
        output.flush()
    }

    private fun writeError(output: OutputStream, statusCode: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        writeResponse(
            output = output,
            statusCode = statusCode,
            contentType = "text/plain; charset=utf-8",
            contentLength = bytes.size.toLong(),
            contentRange = null,
            acceptRanges = null,
            body = ByteArrayInputStream(bytes),
            headOnly = false,
        )
    }

    private fun resolveHostAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        address.hostAddress?.contains(':') == false &&
                        address.isSiteLocalAddress
                }
                ?.hostAddress
                ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
