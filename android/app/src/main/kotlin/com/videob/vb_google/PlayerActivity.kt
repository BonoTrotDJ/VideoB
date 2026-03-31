package com.videob.vb_google

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.view.KeyEvent
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
    private lateinit var loadingOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        webView = findViewById(R.id.player_webview)
        statusView = findViewById(R.id.player_status)
        progressBar = findViewById(R.id.player_progress)
        loadingOverlay = findViewById(R.id.player_loading_overlay)

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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Blocca qualsiasi apertura di nuova finestra/popup
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Blocca navigazione verso nuovi URL (link esterni, reindirizzamenti pub)
                val reqUrl = request?.url?.toString() ?: return false
                val current = view?.url ?: return false
                // Permetti solo lo stesso host o URL sorgente
                return try {
                    val reqHost = android.net.Uri.parse(reqUrl).host ?: ""
                    val curHost = android.net.Uri.parse(current).host ?: ""
                    reqHost != curHost
                } catch (_: Exception) { true }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                loadingOverlay.visibility = View.GONE
                stripOverlays(view)
                injectPlayerControls(view)
            }
        }
    }

    private fun loadSource(url: String) {
        loadingOverlay.visibility = View.VISIBLE
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
              </style>
            </head>
            <body>
              <video id="v" autoplay playsinline src="$url"></video>

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
