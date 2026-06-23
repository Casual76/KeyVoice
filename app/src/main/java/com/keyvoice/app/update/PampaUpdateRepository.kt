package com.keyvoice.app.update

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.keyvoice.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val DEFAULT_MANIFEST_URL =
    "https://raw.githubusercontent.com/Casual76/KeyVoice/main/manifest.json"
private val USER_AGENT = "KeyVoiceUpdater/${BuildConfig.VERSION_NAME}"

class PampaUpdateRepository(
    context: Context,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val manifestReader: suspend (String) -> String = ::readUrlText
) : AppUpdateRepository {
    private val appContext = context.applicationContext
    private val installer = AndroidAppUpdateInstaller(appContext)
    private val gson = Gson()

    override suspend fun checkForStableUpdate(
        currentVersionName: String,
        ignoredVersion: String
    ): Result<AvailableAppUpdate?> = PampaUpdateChecker(
        manifestUrl = manifestUrl,
        manifestReader = manifestReader,
        gson = gson
    ).checkForStableUpdate(currentVersionName, ignoredVersion)

    override fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> {
        return installer.install(update)
    }
}

internal class PampaUpdateChecker(
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val manifestReader: suspend (String) -> String = ::readUrlText,
    private val gson: Gson = Gson()
) {
    suspend fun checkForStableUpdate(
        currentVersionName: String,
        ignoredVersion: String = ""
    ): Result<AvailableAppUpdate?> = runCatching {
        val manifestText = manifestReader(manifestUrl)
        val update = PampaUpdateParser.parseStableUpdate(manifestText, gson)
        when {
            update.version == ignoredVersion -> null
            !isStableVersionNewer(update.version, currentVersionName) -> null
            else -> update
        }
    }
}

internal object PampaUpdateParser {
    fun parseStableUpdate(
        manifestText: String,
        gson: Gson = Gson()
    ): AvailableAppUpdate {
        val manifest = gson.fromJson(manifestText, PampaAppManifest::class.java)
        val app = manifest.app ?: error("Manifest Pampa non valido.")
        val stable = app.stable ?: error("Nessuna release stable disponibile.")
        val repository = app.repository ?: error("Repository release mancante nel manifest.")
        if (stable.apkAsset.isBlank()) error("Asset APK mancante nel manifest.")
        if (stable.releaseTag.isBlank()) error("Release tag mancante nel manifest.")

        val downloadUrl =
            "https://github.com/${repository.repoOwner}/${repository.repoName}/releases/download/" +
                "${stable.releaseTag}/${stable.apkAsset}"

        return AvailableAppUpdate(
            version = stable.version,
            changelog = stable.changelog,
            releaseTag = stable.releaseTag,
            apkAsset = stable.apkAsset,
            downloadUrl = downloadUrl,
            sizeBytes = stable.sizeBytes
        )
    }
}

internal suspend fun downloadUpdateApk(
    context: Context,
    update: AvailableAppUpdate,
    onProgress: (Float, Long, Long) -> Unit
): File = withContext(Dispatchers.IO) {
    val updateDir = File(context.cacheDir, "app_updates").apply { mkdirs() }
    val target = File(updateDir, update.apkAsset.ifBlank { "keyvoice-${update.version}.apk" })
    val temp = File(updateDir, "${target.name}.part")
    temp.delete()

    var connection: HttpURLConnection? = null
    try {
        var currentUrl = update.downloadUrl
        var redirects = 0
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrBlank() && redirects < 6) {
                    connection.disconnect()
                    currentUrl = location
                    redirects++
                    continue
                }
            }
            if (code !in 200..299) {
                error("Download APK fallito ($code).")
            }
            break
        }

        val finalConnection = connection ?: error("Connessione download non disponibile.")
        val total = finalConnection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
        var downloaded = 0L
        finalConnection.inputStream.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (total > 0) {
                        downloaded.toFloat() / total.toFloat()
                    } else {
                        0f
                    }
                    onProgress(progress.coerceIn(0f, 1f), downloaded, total)
                }
            }
        }
        if (update.sizeBytes > 0 && downloaded != update.sizeBytes) {
            error("La dimensione del file scaricato non coincide con i metadati della release.")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) error("Impossibile finalizzare l'APK scaricato.")
        target
    } finally {
        connection?.disconnect()
        if (temp.exists()) temp.delete()
    }
}

private suspend fun readUrlText(url: String): String = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 25_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Richiesta aggiornamenti fallita ($code).")
        body
    } finally {
        connection.disconnect()
    }
}

private data class PampaAppManifest(
    val app: PampaApp?
)

private data class PampaApp(
    val repository: PampaRepository?,
    val stable: PampaVersion?
)

private data class PampaRepository(
    val repoOwner: String,
    val repoName: String
)

private data class PampaVersion(
    val version: String = "",
    val changelog: String = "",
    val releaseTag: String = "",
    @SerializedName("apkAsset") val apkAsset: String = "",
    val sizeBytes: Long = 0L
)
