package com.videob.vb_google

import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import java.net.URI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Bridge esposto alla pagina player dell'embed. La WebView TV invia la POST
 * /turnstile/<id> con un'impronta che il server rifiuta (403), mentre le
 * stesse richieste fatte via OkHttp con User-Agent e intestazioni da browser
 * normale passano. La pagina iniettata da PlayerActivity reindirizza la POST
 * a questo bridge, che la esegue con OkHttp e restituisce il JSON.
 */
class VideobBridge(private val webViewUserAgent: () -> String) {
    private val tag = "VideoBBridge"

    private val chromeUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * Esegue la POST /turnstile/<id> e restituisce il corpo JSON della risposta.
     * Prova prima con User-Agent Chrome senza cookie, poi con i cookie della
     * WebView, infine con lo User-Agent della WebView.
     */
    @JavascriptInterface
    fun postTurnstile(url: String, referer: String, body: String): String {
        Log.w(tag, "postTurnstile url=$url bodyLen=${body.length}")
        return runCatching {
            val origin = runCatching {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}"
            }.getOrDefault("")

            val attempts = listOf(
                Pair(chromeUserAgent, false),
                Pair(chromeUserAgent, true),
                Pair(webViewUserAgent(), true),
            )
            var lastBody = ""
            for ((userAgent, withCookies) in attempts) {
                runCatching {
                    val builder = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .header("Referer", referer)
                        .header("Origin", origin)
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Accept", "*/*")
                        .post(
                            body.toRequestBody(
                                "application/x-www-form-urlencoded".toMediaType(),
                            ),
                        )
                    if (withCookies) {
                        CookieManager.getInstance().getCookie(url)?.let { cookie ->
                            builder.header("Cookie", cookie)
                        }
                    }
                    NetworkClientFactory.get(false)
                        .newCall(builder.build())
                        .execute()
                        .use { response ->
                            lastBody = response.body?.string().orEmpty()
                            Log.w(
                                tag,
                                "postTurnstile code=${response.code} ua=${if (userAgent == chromeUserAgent) "chrome" else "webview"} cookies=$withCookies len=${lastBody.length}",
                            )
                            if (response.isSuccessful) {
                                return lastBody
                            }
                        }
                }.onFailure { error ->
                    Log.w(tag, "postTurnstile attempt failed: ${error.message}")
                }
            }
            lastBody.ifEmpty { """{"success":false}""" }
        }.getOrDefault("""{"success":false}""")
    }
}
