package com.videob.vb_google

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.VpnService
import android.text.InputType
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private var pendingDnsVpnResult: MethodChannel.Result? = null
    private var pendingDnsVpnEnable = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelName,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "openUrl" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    val dohEnabled = call.argument<Boolean>("dohEnabled") == true
                    if (url.isBlank()) {
                        result.error("invalid_url", "URL non valido.", null)
                        return@setMethodCallHandler
                    }

                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URL, url)
                        putExtra(PlayerActivity.EXTRA_DOH_ENABLED, dohEnabled)
                    }
                    startActivity(intent)
                    result.success(null)
                }

                "extractLinks" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    val dohEnabled = call.argument<Boolean>("dohEnabled") == true
                    if (url.isBlank()) {
                        result.error("invalid_url", "URL non valido.", null)
                        return@setMethodCallHandler
                    }

                    executor.execute {
                        try {
                            val links = StreamExtractor.extractLinks(url, dohEnabled)
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

                "editText" -> {
                    val title = call.argument<String>("title").orEmpty()
                    val initialValue = call.argument<String>("initialValue").orEmpty()
                    val hint = call.argument<String>("hint").orEmpty()
                    val isUrl = call.argument<Boolean>("isUrl") == true

                    runOnUiThread {
                        showSystemTextEditor(
                            title = title,
                            initialValue = initialValue,
                            hint = hint,
                            isUrl = isUrl,
                            onSubmit = { value -> result.success(value) },
                            onCancel = { result.success(null) },
                        )
                    }
                }

                "setDnsVpnEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") == true
                    handleDnsVpnToggle(enabled, result)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun handleDnsVpnToggle(enabled: Boolean, result: MethodChannel.Result) {
        if (!enabled) {
            DnsVpnService.stop(this)
            result.success(true)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            DnsVpnService.start(this)
            result.success(true)
            return
        }

        pendingDnsVpnResult = result
        pendingDnsVpnEnable = true
        startActivityForResult(prepareIntent, REQUEST_PREPARE_DNS_VPN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PREPARE_DNS_VPN) {
            return
        }

        val callback = pendingDnsVpnResult
        pendingDnsVpnResult = null

        if (resultCode == RESULT_OK && pendingDnsVpnEnable) {
            DnsVpnService.start(this)
            callback?.success(true)
        } else {
            callback?.success(false)
        }
        pendingDnsVpnEnable = false
    }

    private fun showSystemTextEditor(
        title: String,
        initialValue: String,
        hint: String,
        isUrl: Boolean,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit,
    ) {
        val editText = EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
            this.hint = hint
            maxLines = 3
            isSingleLine = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
            val verticalPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            inputType = if (isUrl) {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("Conferma") { dialogInterface, _ ->
                onSubmit(editText.text?.toString().orEmpty())
                dialogInterface.dismiss()
            }
            .setNegativeButton("Annulla") { dialogInterface, _ ->
                onCancel()
                dialogInterface.dismiss()
            }
            .setOnCancelListener {
                onCancel()
            }
            .create()

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit(editText.text?.toString().orEmpty())
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.setOnShowListener {
            editText.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
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

    companion object {
        private const val REQUEST_PREPARE_DNS_VPN = 1201
    }
}

