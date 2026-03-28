package com.videob.vb_google

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channelName = "videob/channel"
    private val executor = Executors.newSingleThreadExecutor()
    private val backupFileName = "videob_lists_backup.json"
    private val backupFileNamePrefix = "videob_lists_backup"
    private val backupRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/VideoB"

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

                "backupLists" -> {
                    val payload = call.argument<String>("payload").orEmpty()
                    if (payload.isBlank()) {
                        result.error("invalid_payload", "Payload backup non valido.", null)
                        return@setMethodCallHandler
                    }

                    executor.execute {
                        try {
                            writeBackupPayload(payload)
                            runOnUiThread { result.success(null) }
                        } catch (error: Exception) {
                            runOnUiThread {
                                result.error(
                                    "backup_failed",
                                    error.message ?: "Backup fallito.",
                                    null,
                                )
                            }
                        }
                    }
                }

                "loadBackupLists" -> {
                    executor.execute {
                        try {
                            val payload = readBackupPayload()
                            runOnUiThread { result.success(payload) }
                        } catch (error: Exception) {
                            runOnUiThread {
                                result.error(
                                    "backup_read_failed",
                                    error.message ?: "Lettura backup fallita.",
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

    private fun writeBackupPayload(payload: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val existingUri = findBackupUri()
            val targetUri = existingUri ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, backupFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, backupRelativePath)
                },
            ) ?: throw IllegalStateException("Impossibile creare il file backup.")

            resolver.openOutputStream(targetUri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(payload)
            } ?: throw IllegalStateException("Impossibile scrivere il file backup.")
            return
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(downloads, "VideoB").apply { mkdirs() }
        File(directory, backupFileName).writeText(payload)
    }

    private fun readBackupPayload(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findBackupUri() ?: return null
            return contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(File(downloads, "VideoB"), backupFileName)
        if (!file.exists()) {
            return null
        }
        return file.readText()
    }

    private fun findBackupUri() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                OpenableColumns.DISPLAY_NAME,
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf("$backupRelativePath/")

            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    if (!displayName.startsWith(backupFileNamePrefix) || !displayName.endsWith(".json")) {
                        continue
                    }

                    val id = cursor.getLong(idIndex)
                    return@use MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(id.toString())
                        .build()
                }
                null
            }
        } else {
            null
        }
}
