package com.videob.vb_google

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channelName = "videob/channel"
    private val executor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelName,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "openUrl" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    if (url.isBlank()) {
                        result.error("invalid_url", "URL non valido.", null)
                        return@setMethodCallHandler
                    }

                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URL, url)
                    }
                    startActivity(intent)
                    result.success(null)
                }

                "extractLinks" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    if (url.isBlank()) {
                        result.error("invalid_url", "URL non valido.", null)
                        return@setMethodCallHandler
                    }

                    executor.execute {
                        try {
                            val links = StreamExtractor.extractLinks(url)
                            runOnUiThread { result.success(links) }
                        } catch (error: Exception) {
                            runOnUiThread {
                                result.error(
                                    "extract_failed",
                                    error.message ?: "Analisi fallita.",
                                    null,
                                )
                            }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }
}
