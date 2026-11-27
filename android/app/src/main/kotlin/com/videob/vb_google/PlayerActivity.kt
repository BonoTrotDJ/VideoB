package com.videob.vb_google

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.KeyEvent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.flutter.plugin.common.MethodChannel
import java.io.FilterInputStream
import okhttp3.Request

class PlayerActivity : Activity() {
    private val logTag = "VideoBPlayer"
    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: View
    private lateinit var browserButton: Button
    private var exoPlayer: ExoPlayer? = null
    private var dohEnabled: Boolean = false
    private var sourceUrl: String = ""
    private var sourceReferer: String = ""
    private var webViewRetryCount: Int = 0
    private var embedBlocked: Boolean = false
    private var challengeRetryCount: Int = 0
    private var pendingRetryRunnable: Runnable? = null
    private var recreateCount: Int = 0
    private var recreateScheduled: Boolean = false
    private var nativeRetryCount: Int = 0
    private var nativeRetryRunnable: Runnable? = null
    private var errorCloseRunnable: Runnable? = null
    private var tokenMessageActive: Boolean = false
    private val videobBridge = VideobBridge(
        webViewUserAgent = { runCatching { webView.settings.userAgentString }.getOrDefault("") },
        onTokenAcquiring = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && !recreateScheduled) {
                    tokenMessageActive = true
                    statusView.visibility = View.VISIBLE
                    statusView.text = "Token in acquisizione, la trasmissione sarà pronta tra pochi secondi"
                    progressBar.visibility = View.VISIBLE
                    loadingOverlay.visibility = View.VISIBLE
                }
            }
        },
        onPlaybackStarted = {
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                // Il video sta riproducendo: il messaggio del token può
                // lasciare il posto alla trasmissione.
                tokenMessageActive = false
                loadingOverlay.visibility = View.GONE
            }
        },
        onStreamOffline = { reloads ->
            runOnUiThread {
                tokenMessageActive = false
                if (isFinishing || isDestroyed || recreateScheduled) {
                    return@runOnUiThread
                }
                statusView.visibility = View.VISIBLE
                if (reloads < MAX_EMBED_RELOADS) {
                    statusView.text = "Stream non ancora attivo, riprovo automaticamente..."
                    return@runOnUiThread
                }
                // I ricaricamenti della pagina non sono bastati: replico l'azione
                // che l'utente fa a mano, cioè esco dal player e lo riapro.
                // Il contatore nell'intent evita cicli infiniti se il canale è
                // davvero offline.
                if (recreateCount >= MAX_PLAYER_RECREATES) {
                    Log.w(logTag, "stream still down after recreate #$recreateCount, giving up")
                    statusView.text = "Canale momentaneamente offline, riprova più tardi."
                    return@runOnUiThread
                }
                recreateScheduled = true
                Log.w(logTag, "embed still down after $reloads reloads, reopening full flow (attempt ${recreateCount + 1}/$MAX_PLAYER_RECREATES)")
                statusView.text = "Riavvio il canale..."
                val runnable = Runnable {
                    nativeRetryRunnable = null
                    if (isFinishing || isDestroyed) {
                        return@Runnable
                    }
                    releasePlayer()
                    webView.stopLoading()
                    // Riapertura completa: chiede a Dart di rifare il percorso
                    // (risoluzione stream inclusa), identico all'uscita e alla
                    // riapertura manuale del link. Se il canale non è
                    // raggiungibile neanche così, il guard sul contatore evita
                    // i cicli infiniti.
                    val channel = MainActivity.dartChannel
                    if (channel != null) {
                        channel.invokeMethod(
                            "reopenStream",
                            mapOf(
                                "url" to sourceUrl,
                                "recreateCount" to recreateCount,
                            ),
                            object : MethodChannel.Result {
                                override fun success(result: Any?) {}
                                override fun error(
                                    errorCode: String,
                                    errorMessage: String?,
                                    errorDetails: Any?,
                                ) {
                                    Log.w(logTag, "reopenStream error code=$errorCode, restarting player directly")
                                    restartPlayerDirectly()
                                }

                                override fun notImplemented() {
                                    Log.w(logTag, "reopenStream not implemented, restarting player directly")
                                    restartPlayerDirectly()
                                }
                            },
                        )
                        finish()
                    } else {
                        Log.w(logTag, "dart channel unavailable, restarting player directly")
                        restartPlayerDirectly()
                    }
                }
                nativeRetryRunnable = runnable
                webView.postDelayed(runnable, REOPEN_DELAY_MS)
            }
        },
    )

    /**
     * Fallback senza Dart: riavvia l'Activity del player con il contatore
     * aggiornato (equivale a uscire e riaprire la stessa pagina).
     */
    private fun restartPlayerDirectly() {
        if (isFinishing || isDestroyed) {
            return
        }
        val next = Intent(this, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, sourceUrl)
            .putExtra(EXTRA_REFERER, sourceReferer)
            .putExtra(EXTRA_DOH_ENABLED, dohEnabled)
            .putExtra(EXTRA_RECREATE_COUNT, recreateCount + 1)
        startActivity(next)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        webView = findViewById(R.id.player_webview)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.player_status)
        progressBar = findViewById(R.id.player_progress)
        loadingOverlay = findViewById(R.id.player_loading_overlay)
        browserButton = findViewById(R.id.player_browser_button)
        browserButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sourceUrl)))
            }.onFailure { error ->
                Log.w(logTag, "unable to open source in browser", error)
                statusView.text = "Nessun browser disponibile per aprire il canale."
            }
        }
        webView.keepScreenOn = true
        playerView.keepScreenOn = true
        loadingOverlay.keepScreenOn = true
        // Bridge per la POST /turnstile: la WebView TV viene rifiutata dal
        // server (403), OkHttp con User-Agent da browser passa.
        webView.addJavascriptInterface(videobBridge, "VideobBridge")

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        dohEnabled = intent.getBooleanExtra(EXTRA_DOH_ENABLED, false)
        recreateCount = intent.getIntExtra(EXTRA_RECREATE_COUNT, 0)
        if (recreateCount > 0) {
            Log.w(logTag, "player restarted by recreate #$recreateCount")
        }
        if (url.isBlank()) {
            statusView.text = "URL non valido."
            return
        }

        configureWebView()
        loadSource(url, referer)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        val mobileChromeUserAgent = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = mobileChromeUserAgent
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Blocca qualsiasi apertura di nuova finestra/popup
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(logTag, "console[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                request?.let {
                    Log.d(
                        logTag,
                        "web request frame=${it.isForMainFrame} method=${it.method} url=${diagnosticUrl(it.url.toString())}",
                    )
                }
                if (!dohEnabled) {
                    // Anche senza DoH instradamo le navigazioni verso gli embed
                    // (frame /e/<id>): il server player esige le intestazioni
                    // Sec-Fetch e molte WebView TV non le inviano correttamente,
                    // ricevendo 403 / "Not allowed" (schermo nero).
                    val targetEarly = request?.url?.toString()?.trim().orEmpty()
                    val embedPathEarly =
                        runCatching { android.net.Uri.parse(targetEarly).path.orEmpty() }
                            .getOrDefault("")
                    val headersEarly = request?.requestHeaders.orEmpty()
                    val fetchModeEarly = headersEarly.entries
                        .firstOrNull { it.key.equals("Sec-Fetch-Mode", ignoreCase = true) }
                        ?.value
                    val acceptEarly = headersEarly.entries
                        .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                        ?.value
                    val isNavigationEarly = fetchModeEarly.equals("navigate", true) ||
                        (acceptEarly?.contains("text/html") == true &&
                            request?.method.equals("GET", true))
                    val looksLikeEmbedEarly =
                        embedPathEarly.startsWith("/e/") ||
                            targetEarly.contains(".dynbush.net")
                    if (!(isNavigationEarly && looksLikeEmbedEarly)) {
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                val target = request?.url?.toString()?.trim().orEmpty()
                if (target.isBlank() ||
                    target.startsWith("data:") ||
                    target.startsWith("blob:") ||
                    target.startsWith("file:") ||
                    target.startsWith("http://127.0.0.1") ||
                    target.startsWith("http://localhost")
                ) {
                    return super.shouldInterceptRequest(view, request)
                }
                val webRequest = request ?: return null
                val requestHeaders = webRequest.requestHeaders.orEmpty()
                val fetchMode = requestHeaders.entries
                    .firstOrNull { it.key.equals("Sec-Fetch-Mode", ignoreCase = true) }
                    ?.value
                val accept = requestHeaders.entries
                    .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value
                val isNavigation = fetchMode.equals("navigate", true) ||
                    (accept?.contains("text/html") == true &&
                        webRequest.method.equals("GET", true))
                val embedPath = runCatching {
                    android.net.Uri.parse(target).path.orEmpty()
                }.getOrDefault("")
                val looksLikeEmbed = embedPath.startsWith("/e/") ||
                    target.contains(".dynbush.net")

                return runCatching {
                    val requestBuilder = Request.Builder().url(target)
                    for ((name, value) in requestHeaders) {
                        if (!name.equals("Accept-Encoding", ignoreCase = true) &&
                            !name.startsWith("Sec-Fetch", ignoreCase = true)
                        ) {
                            requestBuilder.header(name, value)
                        }
                    }
                    // Il server embed rifiuta le richieste senza intestazioni
                    // Sec-Fetch: si forniscono valori validi quando la WebView
                    // non li invia (frame = iframe, pagina = document).
                    val siteHeader = requestHeaders.entries
                        .firstOrNull { it.key.equals("Sec-Fetch-Site", ignoreCase = true) }
                        ?.value
                        ?: if (webRequest.isForMainFrame) "none" else "cross-site"
                    val destHeader = requestHeaders.entries
                        .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                        ?.value
                        ?: if (webRequest.isForMainFrame) "document" else "iframe"
                    requestBuilder.header("Sec-Fetch-Site", siteHeader)
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Dest", destHeader)
                    if (webRequest.isForMainFrame) {
                        requestBuilder.header("Sec-Fetch-User", "?1")
                    }
                    if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        requestBuilder.header("User-Agent", webView.settings.userAgentString)
                    }
                    CookieManager.getInstance().getCookie(target)?.let { cookie ->
                        requestBuilder.header("Cookie", cookie)
                    }
                    requestBuilder.header("Accept-Encoding", "identity")

                    val response = NetworkClientFactory.get(dohEnabled)
                        .newCall(requestBuilder.build())
                        .execute()
                    if (isNavigation && !webRequest.isForMainFrame && response.code >= 400) {
                        Log.w(
                            logTag,
                            "embed navigation failed via proxy code=${response.code} url=${diagnosticUrl(target)}",
                        )
                        webView.post { showBrowserFallback() }
                    }
                    val body = response.body ?: run {
                        response.close()
                        return@runCatching null
                    }
                    val contentType = body.contentType()
                    val mimeType = contentType?.let { "${it.type}/${it.subtype}" }
                        ?: guessMimeType(target)
                    val encoding = contentType?.charset(Charsets.UTF_8)?.name() ?: "utf-8"
                    val headers = response.headers.toMultimap()
                        .mapValues { (_, values) -> values.joinToString(", ") }
                        .toMutableMap()
                    response.headers("Set-Cookie").forEach { cookie ->
                        CookieManager.getInstance().setCookie(target, cookie)
                    }
                    headers.remove("content-encoding")
                    // Header hop-by-hop / non validi su una risposta
                    // ricostruita: content-length non corrisponde più al
                    // body (soprattutto sull'HTML patchato), transfer-encoding
                    // e set-cookie non vanno inoltrati alla WebView.
                    headers.remove("content-length")
                    headers.remove("transfer-encoding")
                    headers.remove("set-cookie")

                    // Pagina player embed: inietta l'hook che devia la POST
                    // /turnstile/<id> verso il bridge OkHttp e rimuove lo
                    // script dei popunder (finestre pubblicitarie).
                    val isHtmlResponse = mimeType.contains("html", ignoreCase = true)
                    if (isNavigation && looksLikeEmbed && response.isSuccessful && isHtmlResponse) {
                        val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        val html = body.string()
                        val patched = patchEmbedHtml(html)
                        response.close()
                        Log.d(logTag, "embed page patched len=${patched.length} url=${diagnosticUrl(target)}")
                        return@runCatching WebResourceResponse(
                            mimeType,
                            encoding,
                            response.code,
                            response.message.ifBlank { "OK" },
                            headers,
                            patched.byteInputStream(charset),
                        )
                    }

                    val stream = object : FilterInputStream(body.byteStream()) {
                        override fun close() {
                            super.close()
                            response.close()
                        }
                    }
                    WebResourceResponse(
                        mimeType,
                        encoding,
                        response.code,
                        response.message.ifBlank { "OK" },
                        headers,
                        stream,
                    )
                }.onFailure { error ->
                    Log.w(
                        logTag,
                        "web intercept failed url=${diagnosticUrl(target)} error=${error.message}",
                    )
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Blocca navigazione verso nuovi URL (link esterni, reindirizzamenti pub)
                val reqUrl = request?.url?.toString() ?: return false
                // I player sono spesso caricati in iframe cross-domain; consenti
                // la navigazione dei frame, continuando a bloccare popup e link
                // esterni aperti dalla pagina principale.
                if (request?.isForMainFrame == false) return false
                val current = view?.url ?: return false
                // Permetti solo lo stesso host o URL sorgente
                return try {
                    val reqHost = android.net.Uri.parse(reqUrl).host ?: ""
                    val curHost = android.net.Uri.parse(current).host ?: ""
                    reqHost != curHost
                } catch (_: Exception) { true }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request != null) {
                    Log.w(
                        logTag,
                        "web load error frame=${request.isForMainFrame} url=${diagnosticUrl(request.url.toString())} code=${error?.errorCode} description=${error?.description}",
                    )
                }
                if (request?.isForMainFrame == true) {
                    retryWebViewOnce()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request != null && errorResponse != null && errorResponse.statusCode >= 400) {
                    Log.w(
                        logTag,
                        "web HTTP error frame=${request.isForMainFrame} url=${diagnosticUrl(request.url.toString())} code=${errorResponse.statusCode} mime=${errorResponse.mimeType} reason=${errorResponse.reasonPhrase}",
                    )
                }
                // Un 403 sul frame principale è in genere la pagina di verifica
                // antabot (es. Cloudflare): il suo JavaScript gira nella WebView
                // e al termine ricarica la pagina da solo. Ricaricare subito
                // annullerebbe la verifica; si riprova solo dopo un intervallo
                // ampio e solo se nel frattempo la pagina non si è già mossa.
                if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) {
                    if (challengeRetryCount < 2) {
                        challengeRetryCount++
                        scheduleDelayedWebViewRetry(delayMs = 8_000L)
                    } else {
                        // Più 403 di fila nonostante i retry: blocco reale,
                        // non una verifica; propone il browser esterno.
                        Log.w(logTag, "main frame still 403 after retries; offering browser fallback")
                        showBrowserFallback()
                    }
                    return
                }
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    retryWebViewOnce()
                }
                val failedHost = request?.url?.host.orEmpty()
                if (request?.isForMainFrame == false &&
                    errorResponse?.statusCode == 403 &&
                    failedHost.endsWith(".dynbush.net")) {
                    showBrowserFallback()
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(logTag, "page started url=${diagnosticUrl(url)}")
                // La pagina si è mossa (o la verifica è completata): annulla
                // l'eventuale retry in coda per non sovrascrivere la nuova pagina.
                cancelPendingWebViewRetry()
                view.evaluateJavascript(disguiseAsChromeJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(logTag, "page finished url=$url title=${view.title}")
                stripOverlays(view)
                injectPlayerControls(view)
                view.evaluateJavascript(
                    "(function(){return {title:document.title,text:(document.body&&document.body.innerText||'').slice(0,500),videos:document.querySelectorAll('video').length,iframes:document.querySelectorAll('iframe').length}})()",
                ) { result ->
                    Log.d(logTag, "page state url=$url state=$result")
                    val title = view.title.orEmpty()
                    if (title.equals("Not allowed", ignoreCase = true) ||
                        result.contains("Not allowed") || result.contains("domain protected")) {
                        showConnectionError("Il canale ha rifiutato l'apertura dell'embed.")
                    } else if (looksLikeSecurityChallenge(title, result)) {
                        // Verifica antabot in corso: lascia girare il suo
                        // JavaScript; al termine la pagina si ricarica da sola.
                        Log.d(logTag, "security challenge detected on url=$url; waiting for it to complete")
                        statusView.text = "Verifica di sicurezza in corso, attendi..."
                        progressBar.visibility = View.VISIBLE
                        loadingOverlay.visibility = View.VISIBLE
                    } else if (!embedBlocked && !tokenMessageActive) {
                        loadingOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun diagnosticUrl(rawUrl: String): String = runCatching {
        val uri = android.net.Uri.parse(rawUrl)
        uri.buildUpon().clearQuery().fragment(null).build().toString()
    }.getOrDefault("<url non valida>")

    /**
     * Prepara la pagina player dell'embed per la WebView TV:
     *  1. rimuove lo script dei popunder (finestre pubblicitarie);
     *  2. inietta un hook su window.fetch che devia la POST /turnstile/<id>
     *     verso VideobBridge (OkHttp), perché la WebView TV riceve 403.
     */
    private fun patchEmbedHtml(html: String): String {
        val hook = """
            <script>
            (function() {
              if (window.__vbTurnstileHook) return;
              window.__vbTurnstileHook = true;
              try {
                var _vbFetch = window.fetch;
                window.fetch = function(input, init) {
                  try {
                    var url = (typeof input === 'string') ? input : (input && input.url) || '';
                    var method = ((init && init.method) || (input && input.method) || 'GET').toString().toUpperCase();
                    if (method === 'POST' && url.indexOf('/turnstile/') !== -1) {
                      var body = (init && typeof init.body === 'string') ? init.body : (init && init.body ? String(init.body) : '');
                      var abs = new URL(url, location.href).href;
                      if (window.VideobBridge && window.VideobBridge.postTurnstile) {
                        var json = window.VideobBridge.postTurnstile(abs, location.href, body);
                        return Promise.resolve(new Response(json, {status: 200, headers: {'Content-Type': 'application/json'}}));
                      }
                    }
                  } catch (e) {}
                  return _vbFetch.apply(window, arguments);
                };
                window.open = function() { return null; };
              } catch (e) {}
              // Token non ancora pronto: avvisa l'app per mostrare il messaggio
              // "Token in acquisizione..." fin da subito.
              if (window.VideobBridge && window.VideobBridge.notifyTokenAcquiring) {
                window.VideobBridge.notifyTokenAcquiring();
              }
              // Quando il video riproduce davvero, chiudi l'overlay di
              // caricamento (e il messaggio del token) nell'app.
              var vbPlaybackReported = false;
              setInterval(function() {
                if (vbPlaybackReported) return;
                try {
                  var v = document.querySelector('video');
                  if (v && !v.paused && v.readyState >= 2) {
                    vbPlaybackReported = true;
                    if (window.VideobBridge && window.VideobBridge.notifyPlaybackStarted) {
                      window.VideobBridge.notifyPlaybackStarted();
                    }
                  }
                } catch (e) {}
              }, 1000);
              // "STREAM IS OFFLINE" al primo avvio: ricarica la pagina (rifà
              // verifica e prende un nuovo stream), max 2 volte; se l'evento
              // e' davvero offline il messaggio resta a schermo.
              var vbReloads = 0;
              try {
                vbReloads = parseInt(sessionStorage.getItem('__vbOfflineReloads') || '0', 10) || 0;
              } catch (e) {}
              var vbReloading = false;
              var vbGiveUpReported = false;
              setInterval(function() {
                if (vbReloading || vbGiveUpReported) return;
                try {
                  var text = ((document.body && document.body.innerText) || '').toUpperCase();
                  var isDown = text.indexOf('STREAM IS OFFLINE') !== -1 ||
                      text.indexOf('NOT ALLOWED') !== -1 ||
                      text.indexOf('CAPTCHA FAILED') !== -1;
                  if (!isDown) return;
                  if (vbReloads >= 2) {
                    vbGiveUpReported = true;
                    if (window.VideobBridge && window.VideobBridge.notifyStreamOffline) {
                      window.VideobBridge.notifyStreamOffline(vbReloads);
                    }
                    return;
                  }
                  vbReloading = true;
                  vbReloads++;
                  try { sessionStorage.setItem('__vbOfflineReloads', String(vbReloads)); } catch (e) {}
                  if (window.VideobBridge && window.VideobBridge.notifyStreamOffline) {
                    window.VideobBridge.notifyStreamOffline(vbReloads);
                  }
                  setTimeout(function() { location.reload(); }, 2500);
                } catch (e) {}
              }, 1500);
            })();
            </script>
        """.trimIndent()

        var patched = html
        // Popunder: blocco <script> inline con la firma "popundersPerIP"
        // (il contenuto non deve attraversare un </script> precedente).
        val popunderRegex = Regex(
            """<script(?![^>]*\bsrc=)[^>]*>(?:(?!</script>)[\s\S])*?popundersPerIP(?:(?!</script>)[\s\S])*?</script>""",
            RegexOption.IGNORE_CASE,
        )
        patched = popunderRegex.replace(patched, "")

        val lowered = patched.lowercase()
        val injectAt = lowered.lastIndexOf("</body>")
        return if (injectAt >= 0) {
            patched.substring(0, injectAt) + hook + patched.substring(injectAt)
        } else {
            patched + hook
        }
    }

    /**
     * Alcuni provider bloccano la riproduzione se rilevano che la pagina gira
     * dentro una WebView anziche un vero browser desktop (outerWidth/Height a 0,
     * navigator.webdriver, plugin list vuota). Questi segnali vengono mascherati
     * per farla apparire come una normale finestra Chrome.
     */
    private val disguiseAsChromeJs = """
        (function() {
          try {
            Object.defineProperty(navigator, 'webdriver', { get: () => false });
            Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth });
            Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight });
            if (!window.chrome) {
              window.chrome = { runtime: {} };
            }
            if (!navigator.plugins || navigator.plugins.length === 0) {
              Object.defineProperty(navigator, 'plugins', {
                get: () => [1, 2, 3, 4, 5],
              });
            }
            if (!navigator.languages || navigator.languages.length === 0) {
              Object.defineProperty(navigator, 'languages', { get: () => ['it-IT', 'it', 'en-US', 'en'] });
            }
          } catch (e) {}
        })();
    """.trimIndent()

    private fun loadSource(url: String, referer: String = "") {
        sourceUrl = url
        sourceReferer = referer
        webViewRetryCount = 0
        challengeRetryCount = 0
        embedBlocked = false
        tokenMessageActive = false
        cancelPendingWebViewRetry()
        browserButton.visibility = View.GONE
        releasePlayer()
        webView.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        statusView.textSize = 18f
        statusView.text = if (dohEnabled) "Caricamento con DNS 1.1.1.1..." else "Caricamento..."

        if (StreamExtractor.looksLikeDirectMedia(url)) {
            startNativePlayback(url)
            return
        }

        val refererUrl = referer.ifBlank { url }
        webView.loadUrl(
            url,
            mapOf("Referer" to refererUrl),
        )
    }

    private fun retryWebViewOnce() {
        if (webViewRetryCount >= 1 || sourceUrl.isBlank()) {
            showConnectionError()
            return
        }
        webViewRetryCount++
        statusView.text = "Riprovo il collegamento..."
        loadingOverlay.visibility = View.VISIBLE
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val refererUrl = sourceReferer.ifBlank { sourceUrl }
                webView.loadUrl(
                    sourceUrl,
                    mapOf("Referer" to refererUrl),
                )
            }
        }, 1200L)
    }

    /**
     * Riprova il caricamento dopo un ritardo, senza consumare il conteggio dei
     * retry immediati. Usato per lasciare tempo alle verifiche antabot di
     * completarsi nella WebView; se nel frattempo la pagina naviga da sola
     * (verifica superata) il retry viene annullato.
     */
    private fun scheduleDelayedWebViewRetry(delayMs: Long) {
        if (sourceUrl.isBlank()) {
            return
        }
        cancelPendingWebViewRetry()
        statusView.text = "Verifica di sicurezza in corso, attendi..."
        loadingOverlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        val runnable = Runnable {
            pendingRetryRunnable = null
            if (isFinishing || isDestroyed) {
                return@Runnable
            }
            Log.d(logTag, "delayed webview retry after challenge window source=${diagnosticUrl(sourceUrl)}")
            val refererUrl = sourceReferer.ifBlank { sourceUrl }
            webView.loadUrl(
                sourceUrl,
                mapOf("Referer" to refererUrl),
            )
        }
        pendingRetryRunnable = runnable
        webView.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingWebViewRetry() {
        pendingRetryRunnable?.let { webView.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun cancelPendingNativeRetry() {
        nativeRetryRunnable?.let { webView.removeCallbacks(it) }
        nativeRetryRunnable = null
    }

    private fun looksLikeSecurityChallenge(title: String, pageStateJson: String): Boolean {
        val haystack = (title + " " + pageStateJson).lowercase()
        return haystack.contains("just a moment") ||
            haystack.contains("attention required") ||
            haystack.contains("challenge-platform") ||
            haystack.contains("cf-chl") ||
            haystack.contains("verifica di sicurezza") ||
            haystack.contains("checking your browser")
    }

    private fun showConnectionError() {
        showConnectionError("Errore Connessione")
    }

    private fun showConnectionError(message: String) {
        releasePlayer()
        webView.stopLoading()
        webView.loadDataWithBaseURL(
            null,
            "<html><body style='background:#000;color:#fff;font-family:sans-serif;padding:24px;font-size:20px'>${message}</body></html>",
            "text/html",
            "utf-8",
            null,
        )
        progressBar.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        statusView.textSize = 18f
        statusView.text = message
        loadingOverlay.visibility = View.VISIBLE
        // Dopo 4 secondi il player si chiude da solo e si torna alla lista.
        cancelPendingErrorClose()
        val runnable = Runnable {
            errorCloseRunnable = null
            if (!isFinishing && !isDestroyed) {
                Log.w(logTag, "closing player after error message")
                finish()
            }
        }
        errorCloseRunnable = runnable
        webView.postDelayed(runnable, ERROR_CLOSE_DELAY_MS)
    }

    private fun cancelPendingErrorClose() {
        errorCloseRunnable?.let { webView.removeCallbacks(it) }
        errorCloseRunnable = null
    }

    private fun showBrowserFallback() {
        if (embedBlocked) return
        embedBlocked = true
        Log.w(logTag, "embed rejected by provider; offering external browser source=${diagnosticUrl(sourceUrl)}")
        progressBar.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        statusView.textSize = 16f
        statusView.text =
            "Il server video rifiuta il player interno. Apri il canale nel browser: " +
                "al ritorno in app il collegamento viene riprovato automaticamente."
        browserButton.visibility = View.VISIBLE
        loadingOverlay.visibility = View.GONE
    }

    /**
     * Al ritorno in primo piano (tipicamente dopo aver aperto la sorgente nel
     * browser, dove la protezione antabot viene superata) si riprova a caricare
     * il canale nella WebView. La riprova avviene solo se il player è ancora
     * bloccato, così non interferisce con la riproduzione in corso.
     */
    override fun onResume() {
        super.onResume()
        if (embedBlocked && sourceUrl.isNotBlank() && !isFinishing && !isDestroyed) {
            Log.d(logTag, "resume while embed blocked; retrying webview load source=${diagnosticUrl(sourceUrl)}")
            webView.postDelayed({
                if (isFinishing || isDestroyed || !embedBlocked) {
                    return@postDelayed
                }
                embedBlocked = false
                webViewRetryCount = 0
                challengeRetryCount = 0
                tokenMessageActive = false
                browserButton.visibility = View.GONE
                loadingOverlay.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                statusView.visibility = View.VISIBLE
                statusView.text = "Riprovo il collegamento..."
                val refererUrl = sourceReferer.ifBlank { sourceUrl }
                webView.loadUrl(
                    sourceUrl,
                    mapOf("Referer" to refererUrl),
                )
            }, 800L)
        }
    }

    private fun startNativePlayback(url: String) {
        Log.d(logTag, "startNativePlayback url=$url")
        webView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player
        playerView.useController = true
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 5000
        playerView.requestFocus()
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        tokenMessageActive = false
                        loadingOverlay.visibility = View.GONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(logTag, "native playback error url=$url (retry $nativeRetryCount/$MAX_NATIVE_RETRIES)", error)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        // Al primo avvio lo stream può non essere ancora attivo
                        // (m3u8 403/404 finché il canale non si attiva): riprovo
                        // dopo una pausa prima di mostrare l'errore di connessione.
                        if (nativeRetryCount < MAX_NATIVE_RETRIES) {
                            nativeRetryCount++
                            statusView.visibility = View.VISIBLE
                            statusView.text = "Stream non ancora attivo, riprovo automaticamente..."
                            cancelPendingNativeRetry()
                            val runnable = Runnable {
                                nativeRetryRunnable = null
                                if (isFinishing || isDestroyed) {
                                    return@Runnable
                                }
                                Log.w(logTag, "native retry #$nativeRetryCount for url=$url")
                                releasePlayer()
                                startNativePlayback(url)
                            }
                            nativeRetryRunnable = runnable
                            webView.postDelayed(runnable, NATIVE_RETRY_DELAY_MS)
                        } else {
                            showConnectionError()
                        }
                    }
                }
            },
        )
        val mimeType = when {
            url.contains(".m3u8", ignoreCase = true) ||
                url.contains("%2Fm3u8", ignoreCase = true) ||
                url.contains("%2Em3u8", ignoreCase = true) ->
                MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", ignoreCase = true) ||
                url.contains("%2Empd", ignoreCase = true) ->
                MimeTypes.APPLICATION_MPD
            url.contains(".mp4", ignoreCase = true) ||
                url.contains("%2Emp4", ignoreCase = true) ->
                MimeTypes.VIDEO_MP4
            else -> null
        }
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (mimeType != null) {
                    setMimeType(mimeType)
                }
            }
            .build()
        Log.d(logTag, "startNativePlayback mimeType=$mimeType url=$url")
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        playerView.player = null
    }

    private fun guessMimeType(url: String): String =
        when {
            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            url.contains(".ts", ignoreCase = true) -> "video/mp2t"
            url.contains(".mp4", ignoreCase = true) -> "video/mp4"
            url.contains(".m3u", ignoreCase = true) -> "audio/x-mpegurl"
            url.contains(".js", ignoreCase = true) -> "application/javascript"
            url.contains(".css", ignoreCase = true) -> "text/css"
            url.contains(".svg", ignoreCase = true) -> "image/svg+xml"
            url.contains(".png", ignoreCase = true) -> "image/png"
            url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true) -> "image/jpeg"
            else -> "text/html"
        }

    private fun injectPlayerControls(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              // Blocca window.open e target="_blank"
              window.open = function() { return null; };
              document.addEventListener('click', function(e) {
                var t = e.target;
                while (t) {
                  if (t.tagName === 'A' && (t.target === '_blank' || t.getAttribute('onclick'))) {
                    e.preventDefault(); e.stopPropagation(); break;
                  }
                  t = t.parentElement;
                }
              }, true);

              // Controlli tastiera per il video (pagine .php)
              var _vbInited = window._vbInited;
              if (_vbInited) return;
              window._vbInited = true;

              function findVideo() {
                var v = document.querySelector('video');
                if (!v) {
                  // cerca dentro iframe stesso dominio
                  var frames = document.querySelectorAll('iframe');
                  for (var i = 0; i < frames.length; i++) {
                    try { v = frames[i].contentDocument.querySelector('video'); if (v) break; }
                    catch(e) {}
                  }
                }
                return v;
              }

              document.addEventListener('keydown', function(e) {
                var v = findVideo();
                if (!v) return;
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault(); e.stopPropagation();
                  if (v.paused) v.play(); else v.pause();
                } else if (e.key === 'ArrowLeft') {
                  e.preventDefault(); e.stopPropagation();
                  v.currentTime = Math.max(0, v.currentTime - 10);
                } else if (e.key === 'ArrowRight') {
                  e.preventDefault(); e.stopPropagation();
                  v.currentTime = Math.min(v.duration || 0, v.currentTime + 10);
                }
              }, true);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun stripOverlays(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              var selectors = [
                '#html1',
                '#button1',
                '.ads',
                '.ad',
                '.banner',
                '.popup',
                '.popunder'
              ];
              selectors.forEach(function(selector) {
                document.querySelectorAll(selector).forEach(function(node) {
                  node.remove();
                });
              });
              document.body.style.margin = '0';
              document.body.style.background = '#000';
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun videoHtml(url: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                html, body {
                  width: 100%; height: 100%;
                  background: #000; overflow: hidden;
                  font-family: sans-serif; color: #fff;
                }
                video {
                  width: 100%; height: 100%;
                  background: #000; display: block;
                }
                #controls {
                  position: fixed; bottom: 0; left: 0; right: 0;
                  padding: 16px 24px 20px;
                  background: linear-gradient(transparent, rgba(0,0,0,0.85));
                  transition: opacity 0.3s;
                  opacity: 0;
                }
                #controls.visible { opacity: 1; }
                #progress-wrap {
                  width: 100%; height: 6px;
                  background: rgba(255,255,255,0.25);
                  border-radius: 3px; margin-bottom: 12px; cursor: pointer;
                }
                #progress-bar {
                  height: 100%; width: 0%;
                  background: #fff; border-radius: 3px;
                  transition: width 0.2s linear;
                }
                #ctrl-row {
                  display: flex; align-items: center; gap: 20px;
                }
                .ctrl-btn {
                  background: none; border: none; color: #fff;
                  font-size: 28px; cursor: pointer; line-height: 1;
                  padding: 4px 8px; border-radius: 6px;
                  transition: background 0.15s;
                }
                .ctrl-btn:focus, .ctrl-btn.active {
                  background: rgba(255,255,255,0.25); outline: none;
                }
                #time-display {
                  font-size: 15px; margin-left: auto; opacity: 0.8;
                }
                #seek-feedback {
                  position: fixed; top: 50%; left: 50%;
                  transform: translate(-50%, -50%);
                  font-size: 48px; font-weight: bold;
                  opacity: 0; pointer-events: none;
                  text-shadow: 0 2px 8px rgba(0,0,0,0.7);
                  transition: opacity 0.2s;
                }
                #seek-feedback.show { opacity: 1; }
                #error-message {
                  position: fixed;
                  inset: 0;
                  display: none;
                  align-items: center;
                  justify-content: center;
                  flex-direction: column;
                  gap: 16px;
                  background: rgba(0,0,0,0.92);
                  color: #fff;
                  font-size: 22px;
                  text-align: center;
                  z-index: 9999;
                }
                #error-message .icon {
                  width: 64px;
                  height: 64px;
                  border: 2px solid #fff;
                  border-radius: 999px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  font-size: 34px;
                  font-weight: 700;
                }
              </style>
            </head>
            <body>
              <video id="v" autoplay playsinline src="$url"></video>
              <div id="error-message">
                <div class="icon">!</div>
                <div>errore connessione</div>
              </div>

              <div id="seek-feedback"></div>

              <div id="controls">
                <div id="progress-wrap">
                  <div id="progress-bar"></div>
                </div>
                <div id="ctrl-row">
                  <button class="ctrl-btn" id="btn-back" title="−10s">⏪</button>
                  <button class="ctrl-btn" id="btn-play" title="Play/Pausa">⏸</button>
                  <button class="ctrl-btn" id="btn-fwd" title="+10s">⏩</button>
                  <span id="time-display">0:00 / 0:00</span>
                </div>
              </div>

              <script>
                const v = document.getElementById('v');
                const controls = document.getElementById('controls');
                const progressBar = document.getElementById('progress-bar');
                const timeDisplay = document.getElementById('time-display');
                const btnPlay = document.getElementById('btn-play');
                const btnBack = document.getElementById('btn-back');
                const btnFwd = document.getElementById('btn-fwd');
                const seekFeedback = document.getElementById('seek-feedback');
                const errorMessage = document.getElementById('error-message');
                let hideTimer = null;

                function fmt(s) {
                  s = Math.max(0, Math.floor(s));
                  const m = Math.floor(s / 60), sec = s % 60;
                  return m + ':' + String(sec).padStart(2, '0');
                }

                function showControls() {
                  controls.classList.add('visible');
                  clearTimeout(hideTimer);
                  hideTimer = setTimeout(() => controls.classList.remove('visible'), 3000);
                }

                function showSeekFeedback(text) {
                  seekFeedback.textContent = text;
                  seekFeedback.classList.add('show');
                  clearTimeout(seekFeedback._t);
                  seekFeedback._t = setTimeout(() => seekFeedback.classList.remove('show'), 800);
                }

                function togglePlay() {
                  if (v.paused) { v.play(); btnPlay.textContent = '⏸'; }
                  else { v.pause(); btnPlay.textContent = '▶'; }
                  showControls();
                }

                function seek(delta) {
                  v.currentTime = Math.min(v.duration || 0, Math.max(0, v.currentTime + delta));
                  showSeekFeedback(delta > 0 ? '+' + delta + 's' : delta + 's');
                  showControls();
                }

                btnPlay.addEventListener('click', togglePlay);
                btnBack.addEventListener('click', () => seek(-10));
                btnFwd.addEventListener('click', () => seek(10));

                document.getElementById('progress-wrap').addEventListener('click', function(e) {
                  const r = this.getBoundingClientRect();
                  v.currentTime = ((e.clientX - r.left) / r.width) * (v.duration || 0);
                  showControls();
                });

                v.addEventListener('timeupdate', () => {
                  const pct = v.duration ? (v.currentTime / v.duration) * 100 : 0;
                  progressBar.style.width = pct + '%';
                  timeDisplay.textContent = fmt(v.currentTime) + ' / ' + fmt(v.duration || 0);
                });

                v.addEventListener('click', togglePlay);
                v.addEventListener('error', function() {
                  errorMessage.style.display = 'flex';
                  controls.classList.remove('visible');
                });
                v.addEventListener('stalled', function() {
                  if (v.readyState === 0) {
                    errorMessage.style.display = 'flex';
                    controls.classList.remove('visible');
                  }
                });
                v.addEventListener('canplay', function() {
                  errorMessage.style.display = 'none';
                });

                document.addEventListener('keydown', function(e) {
                  switch (e.key) {
                    case ' ':
                    case 'Enter':
                      e.preventDefault();
                      togglePlay();
                      break;
                    case 'ArrowLeft':
                      e.preventDefault();
                      seek(-10);
                      break;
                    case 'ArrowRight':
                      e.preventDefault();
                      seek(10);
                      break;
                    case 'ArrowUp':
                    case 'ArrowDown':
                      e.preventDefault();
                      showControls();
                      break;
                    default:
                      showControls();
                  }
                });

                // Show controls on start
                v.addEventListener('canplay', showControls);
                showControls();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        exoPlayer?.let { player ->
            if (playerView.visibility == View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player.play()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.pause()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val current = player.currentPosition.coerceAtLeast(0L)
                        player.seekTo((current - 10_000L).coerceAtLeast(0L))
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val duration = player.duration
                        val current = player.currentPosition.coerceAtLeast(0L)
                        val target = if (duration == C.TIME_UNSET) {
                            current + 10_000L
                        } else {
                            (current + 10_000L).coerceAtMost(duration)
                        }
                        player.seekTo(target)
                        return true
                    }
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        playerView.showController()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (playerView.isControllerFullyVisible) {
                            playerView.hideController()
                            return true
                        }
                    }
                }
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                webView.evaluateJavascript("""(function(){
                  var v=document.querySelector('video');
                  if(!v)return;
                  if(v.paused)v.play();else v.pause();
                  if(typeof showControls==='function')showControls();
                })();""", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                webView.evaluateJavascript("""(function(){
                  var v=document.querySelector('video');
                  if(!v)return;
                  v.currentTime=Math.max(0,v.currentTime-10);
                  if(typeof showSeekFeedback==='function')showSeekFeedback('-10s');
                  if(typeof showControls==='function')showControls();
                })();""", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                webView.evaluateJavascript("""(function(){
                  var v=document.querySelector('video');
                  if(!v)return;
                  v.currentTime=Math.min(v.duration||0,v.currentTime+10);
                  if(typeof showSeekFeedback==='function')showSeekFeedback('+10s');
                  if(typeof showControls==='function')showControls();
                })();""", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                webView.evaluateJavascript(
                    "if(typeof showControls==='function')showControls();", null)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (webView.canGoBack()) { webView.goBack(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        releasePlayer()
        cancelPendingWebViewRetry()
        cancelPendingNativeRetry()
        cancelPendingErrorClose()
        val parent = webView.parent
        if (parent is ViewGroup) {
            parent.removeView(webView)
        }
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Andando in background (home, app esterna) il video continuerebbe a
        // suonare con lo schermo sempre acceso: metto in pausa; al ritorno
        // in primo piano resta in pausa, l'utente preme play per riprendere.
        exoPlayer?.pause()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_DOH_ENABLED = "extra_doh_enabled"
        const val EXTRA_RECREATE_COUNT = "extra_recreate_count"

        /** Ricaricamenti della pagina embed gestiti dal JS prima del riavvio. */
        const val MAX_EMBED_RELOADS = 2

        /** Quante volte il player si riavvia da solo (equivale a esci+riapri). */
        const val MAX_PLAYER_RECREATES = 1

        /** Ritentativi del player nativo prima dell'errore di connessione. */
        const val MAX_NATIVE_RETRIES = 2

        /** Pausa tra un tentativo e l'altro del player nativo. */
        const val NATIVE_RETRY_DELAY_MS = 8000L

        /** Pausa prima della riapertura completa del canale. */
        const val REOPEN_DELAY_MS = 1500L

        /** Tempo di visualizzazione del messaggio d'errore prima di chiudere. */
        const val ERROR_CLOSE_DELAY_MS = 4000L
    }
}
