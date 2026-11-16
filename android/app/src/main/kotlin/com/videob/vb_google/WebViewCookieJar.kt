package com.videob.vb_google

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * CookieJar di OkHttp appoggiato al CookieManager globale, lo stesso usato
 * dalla WebView dell'app. In questo modo:
 *  - i cookie ricevuti dalle richieste OkHttp (risoluzione stream, proxy
 *    locale, download pagine) vengono conservati e ri-inviati alle chiamate
 *    successive, invece di essere scartati ad ogni richiesta;
 *  - i cookie acquisiti dalla WebView (es. superando una verifica di sicurezza)
 *    vengono riutilizzati anche da OkHttp;
 *  - i cookie vengono persistiti su disco, così l'"autorizzazione" ottenuta
 *    resta valida anche dopo il riavvio dell'app.
 */
object WebViewCookieJar : CookieJar {
    private val manager: CookieManager by lazy { CookieManager.getInstance() }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching { manager.getCookie(url.toString()) }
            .getOrNull()
            ?: return emptyList()
        if (header.isBlank()) {
            return emptyList()
        }
        return header.split(";")
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    null
                } else {
                    runCatching { Cookie.parse(url, trimmed) }.getOrNull()
                }
            }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            return
        }
        runCatching {
            for (cookie in cookies) {
                manager.setCookie(url.toString(), cookie.toString())
            }
            manager.flush()
        }
    }
}
