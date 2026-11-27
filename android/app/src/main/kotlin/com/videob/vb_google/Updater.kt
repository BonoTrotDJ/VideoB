package com.videob.vb_google

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Aggiornamento automatico: all'avvio controlla l'ultima release pubblicata su
 * GitHub (api.github.com/repos/BonoTrotDJ/VideoB/releases/latest); se il tag è
 * più recente della versione installata, scarica l'APK firmato con la chiave
 * fissa del progetto e lancia l'installer di sistema.
 *
 * La firma fissa (key.properties + videob-release.jks, condivisa col workflow
 * release-apk.yml) è indispensabile: Android rifiuta gli aggiornamenti
 * installati sopra un APK firmato con una chiave diversa.
 */
object Updater {
    private const val tag = "VideoBUpdater"
    private const val releasesUrl = "https://api.github.com/repos/BonoTrotDJ/VideoB/releases/latest"
    private const val skipPrefKey = "update_skipped_tag"

    /**
     * Controllo all'avvio (non bloccante): se c'è una versione più recente e
     * l'utente non l'aveva già saltata, mostra la proposta di aggiornamento.
     */
    fun checkOnStartup(activity: Activity) {
        checkAsync(activity, respectSkipPref = true)
    }

    /**
     * Controllo manuale dal menu "Cerca aggiornamento": mostra sempre un
     * esito, anche quando si è già all'ultima versione.
     */
    fun checkManually(activity: Activity) {
        checkAsync(activity, respectSkipPref = false)
    }

    private fun checkAsync(activity: Activity, respectSkipPref: Boolean) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            val update = runCatching { fetchLatestRelease(activity) }
                .getOrElse { error ->
                    Log.w(tag, "update check failed: ${error.message}")
                    activity.runOnUiThread {
                        showSimpleDialog(
                            activity,
                            "Controllo aggiornamento",
                            "Impossibile controllare su GitHub: ${error.message}",
                        )
                    }
                    return@execute
                }
            if (update == null) {
                Log.d(tag, "no newer release available")
                if (!respectSkipPref) {
                    activity.runOnUiThread {
                        showSimpleDialog(
                            activity,
                            "Nessun aggiornamento",
                            "Hai già l'ultima versione (${currentVersionName(activity)}).",
                        )
                    }
                }
                return@execute
            }
            val preferences = activity.getSharedPreferences("videob_updater", 0)
            if (respectSkipPref && preferences.getString(skipPrefKey, null) == update.tagName) {
                Log.d(tag, "release ${update.tagName} skipped by user earlier")
                return@execute
            }
            activity.runOnUiThread { showUpdateDialog(activity, update, preferences) }
        }
    }

    private fun showSimpleDialog(activity: Activity, title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun fetchLatestRelease(activity: Activity): UpdateInfo? {
        val current = currentVersionName(activity)
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VideoB/" + current)
            .build()
        NetworkClientFactory.get(false).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "releases/latest code=${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val tagName = json.optString("tag_name").removePrefix("v")
            val apkUrl = findApkAsset(json) ?: return null
            if (isNewerVersion(tagName, current)) {
                return UpdateInfo(tagName, apkUrl)
            }
            return null
        }
    }

    /** Preferisce l'asset VideoB-<tag>.apk; fallback su app-release.apk. */
    private fun findApkAsset(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                if (name.startsWith("VideoB-")) {
                    return url
                }
                if (name == "app-release.apk") {
                    fallback = url
                }
            }
        }
        return fallback
    }

    private fun currentVersionName(activity: Activity): String = runCatching {
        activity.packageManager
            .getPackageInfo(activity.packageName, 0)
            .versionName.orEmpty()
    }.getOrDefault("")

    /** Confronto "1.0.16" > "1.0.15": componenti numerici. */
    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateParts = candidate.removePrefix("v").split(".")
        val currentParts = current.removePrefix("v").split(".")
        val size = maxOf(candidateParts.size, currentParts.size)
        for (i in 0 until size) {
            val c = candidateParts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val cur = currentParts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (c != cur) {
                return c > cur
            }
        }
        return false
    }

    private fun showUpdateDialog(
        activity: Activity,
        update: UpdateInfo,
        preferences: SharedPreferences,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Aggiornamento disponibile")
            .setMessage(
                "È disponibile la versione ${update.tagName} " +
                    "(installata: ${currentVersionName(activity)}).\n\n" +
                    "Scaricare e installare ora?",
            )
            .setPositiveButton("Aggiorna") { dialog, _ ->
                dialog.dismiss()
                downloadAndInstall(activity, update)
            }
            .setNegativeButton("Non ora") { dialog, _ ->
                dialog.dismiss()
                // Non richiedere lo stesso aggiornamento a ogni avvio.
                preferences.edit().putString(skipPrefKey, update.tagName).apply()
            }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, update: UpdateInfo) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Aggiornamento ${update.tagName}")
            .setMessage("Scaricamento in corso...")
            .setCancelable(false)
            .create()
        progress.show()

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            val destination = File(activity.cacheDir, "update/VideoB-${update.tagName}.apk")
            runCatching {
                destination.parentFile?.mkdirs()
                val request = Request.Builder()
                    .url(update.apkUrl)
                    .header("User-Agent", "VideoB/${currentVersionName(activity)}")
                    .build()
                NetworkClientFactory.get(false).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("download code=${response.code}")
                    }
                    response.body?.byteStream()?.use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("corpo download vuoto")
                }
            }.onSuccess {
                Log.w(tag, "update downloaded: ${destination.absolutePath} (${destination.length()} bytes)")
                activity.runOnUiThread {
                    // Il download dura minuti: l'activity puo essere stata
                    // distrutta nel frattempo (rotazione, chiusura) e
                    // dismiss()/show() su finestra morta crasherebbero.
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    runCatching { progress.dismiss() }
                    installApk(activity, destination)
                }
            }.onFailure { error ->
                Log.w(tag, "update download failed: ${error.message}")
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    runCatching { progress.dismiss() }
                    AlertDialog.Builder(activity)
                        .setTitle("Aggiornamento non riuscito")
                        .setMessage("Scaricamento fallito: ${error.message}")
                        .setPositiveButton("Chiudi", null)
                        .show()
                }
            }.also {
                executor.shutdown()
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            activity.startActivity(installIntent)
        }.onFailure { error ->
            Log.w(tag, "installer not available: $error")
            AlertDialog.Builder(activity)
                .setTitle("Installazione")
                .setMessage(
                    "Impossibile aprire l'installer di sistema.\n" +
                        "Attiva 'Applicazioni da sorgenti sconosciute' per Video BonoTrot " +
                        "nelle impostazioni della TV, poi riprova.",
                )
                .setPositiveButton("Chiudi", null)
                .show()
        }
    }
}

private data class UpdateInfo(
    val tagName: String,
    val apkUrl: String,
)
