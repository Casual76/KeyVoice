package com.keyvoice.app.update

import android.content.Context
import com.keyvoice.app.BuildConfig
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource

/**
 * Dove KeyVoice pubblica le sue release.
 *
 * Il manifest e' lo stesso file che il Pampa Store legge per mostrare l'app: una sola fonte, quindi
 * non esiste il caso in cui lo store offre una versione e l'app in-app ne offre un'altra.
 */
const val KEYVOICE_MANIFEST_URL =
    "https://raw.githubusercontent.com/Casual76/KeyVoice/main/manifest.json"

/**
 * L'aggiornamento in-app di KeyVoice, costruito sul Fluid Engine.
 *
 * Prima di questo file la stessa cosa erano 450 righe qui dentro: scaricare, verificare che l'APK
 * sia davvero questa app, aprire una sessione di `PackageInstaller` e riportare i suoi eventi a chi
 * guarda. Erano corrette, ma erano anche una copia di quelle di ogni altra app, e le correzioni
 * arrivavano in una sola delle copie.
 *
 * Lo `User-Agent` porta la versione perche' e' l'unico modo per leggere dai log delle release quante
 * installazioni sono ferme a una versione vecchia.
 */
fun keyVoiceUpdater(
    context: Context,
    manifestUrl: String = KEYVOICE_MANIFEST_URL,
): AppUpdater {
    val http = EngineHttp(userAgent = "KeyVoiceUpdater/${BuildConfig.VERSION_NAME}")
    return EngineAppUpdater(
        http = http,
        source = UpdateSource(
            manifestUrl = manifestUrl,
            applicationId = context.packageName,
        ),
        // Lo stesso client HTTP del controllo: un secondo client significherebbe un secondo
        // User-Agent e un secondo insieme di timeout da tenere allineati.
        installer = AndroidAppUpdateInstaller(context.applicationContext, http),
    )
}
