package com.videob.vb_google

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView

class PlayerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        webView = findViewById(R.id.player_webview)
        statusView = findViewById(R.id.player_status)
        progressBar = findViewById(R.id.player_progress)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            statusView.text = "URL non valido."
            return
        }

        configureWebView()
        loadSource(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

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
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                statusView.visibility = View.GONE
                stripOverlays(view)
            }
        }
    }

    private fun loadSource(url: String) {
        progressBar.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        statusView.text = "Caricamento..."

        if (StreamExtractor.looksLikeDirectMedia(url)) {
            webView.loadDataWithBaseURL(
                url,
                videoHtml(url),
                "text/html",
                "utf-8",
                null,
            )
            return
        }

        webView.loadUrl(
            url,
            mapOf(
                "Referer" to "https://sportsonline.si/",
                "Origin" to "https://sportsonline.si",
            ),
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
                html, body {
                  margin: 0;
                  padding: 0;
                  width: 100%;
                  height: 100%;
                  background: #000;
                  overflow: hidden;
                }
                video {
                  width: 100%;
                  height: 100%;
                  background: #000;
                }
              </style>
            </head>
            <body>
              <video controls autoplay playsinline src="$url"></video>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        val parent = webView.parent
        if (parent is ViewGroup) {
            parent.removeView(webView)
        }
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
