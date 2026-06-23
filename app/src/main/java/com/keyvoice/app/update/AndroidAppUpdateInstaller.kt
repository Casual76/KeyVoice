package com.keyvoice.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

class AndroidAppUpdateInstaller(
    private val context: Context
) {
    fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> = channelFlow {
        send(AppUpdateInstallState.Verifying("Preparazione aggiornamento..."))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permissionIntent)
            send(
                AppUpdateInstallState.Error(
                    "Abilita l'installazione da origini sconosciute per KeyVoice e riprova."
                )
            )
            return@channelFlow
        }

        val apkFile = runCatching {
            downloadUpdateApk(context, update) { progress, downloaded, total ->
                trySend(AppUpdateInstallState.Downloading(progress, downloaded, total))
            }
        }.getOrElse { error ->
            send(AppUpdateInstallState.Error(error.message ?: "Download aggiornamento non riuscito."))
            return@channelFlow
        }

        send(AppUpdateInstallState.Verifying("Verifica APK..."))
        val packageInfo = context.packageManager.readArchiveInfo(apkFile.absolutePath)
        if (packageInfo == null) {
            send(AppUpdateInstallState.Error("Android non riesce a leggere l'APK scaricato."))
            return@channelFlow
        }

        val validationMessage = validateDownloadedApkMetadata(
            expectedPackageName = context.packageName,
            expectedVersionName = update.version,
            actualPackageName = packageInfo.packageName,
            actualVersionName = packageInfo.versionName
        )
        if (validationMessage != null) {
            send(AppUpdateInstallState.Error(validationMessage))
            return@channelFlow
        }

        runCatching {
            commitPackageInstallerSession(apkFile, packageInfo) { state -> send(state) }
        }.onFailure { error ->
            send(AppUpdateInstallState.Error(error.message ?: "Installazione aggiornamento non riuscita."))
        }
    }

    private suspend fun commitPackageInstallerSession(
        file: File,
        packageInfo: PackageInfo,
        emitState: suspend (AppUpdateInstallState) -> Unit
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageInfo.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }
        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (exception: Exception) {
            throw IllegalStateException(
                exception.message ?: "Impossibile avviare la sessione di installazione.",
                exception
            )
        }

        val events = AppUpdateInstallSessionRegistry.register(sessionId)
        try {
            emitState(AppUpdateInstallState.Installing("Installazione aggiornamento..."))
            withContext(Dispatchers.IO) {
                packageInstaller.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite(file.name, 0, file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val callbackIntent = Intent(context, KeyVoiceUpdateInstallResultReceiver::class.java).apply {
                        action = KeyVoiceUpdateInstallResultReceiver.ACTION_INSTALL_STATUS
                        putExtra(KeyVoiceUpdateInstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        callbackIntent,
                        PendingIntentFlags.mutableUpdateCurrent()
                    )
                    session.commit(pendingIntent.intentSender)
                }
            }
        } catch (error: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            AppUpdateInstallSessionRegistry.unregister(sessionId)
            throw error
        }

        var terminalEvent: AppUpdateInstallState? = null
        events.takeWhile { event ->
            val keepCollecting = event !is AppUpdateInstallState.Installed &&
                event !is AppUpdateInstallState.Error
            if (!keepCollecting) terminalEvent = event
            keepCollecting
        }.collect { emitState(it) }

        terminalEvent?.let { event ->
            emitState(
                if (event is AppUpdateInstallState.Installed && event.filePath.isBlank()) {
                    event.copy(filePath = file.absolutePath)
                } else {
                    event
                }
            )
        }
    }
}

class KeyVoiceUpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(
            EXTRA_SESSION_ID,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        )
        if (sessionId == -1) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = intent.parcelableIntent(Intent.EXTRA_INTENT)
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                }
                AppUpdateInstallSessionRegistry.tryEmit(
                    sessionId,
                    AppUpdateInstallState.AwaitingUserAction("Conferma l'installazione sul dispositivo.")
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateInstallSessionRegistry.tryEmit(
                    sessionId,
                    AppUpdateInstallState.Installed("")
                )
            }
            else -> {
                AppUpdateInstallSessionRegistry.tryEmit(
                    sessionId,
                    AppUpdateInstallState.Error(status.toInstallFailureMessage(statusMessage))
                )
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.keyvoice.app.action.APP_UPDATE_INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "com.keyvoice.app.extra.APP_UPDATE_INSTALL_SESSION_ID"
    }
}

object AppUpdateInstallSessionRegistry {
    private val sessionEvents = ConcurrentHashMap<Int, MutableSharedFlow<AppUpdateInstallState>>()

    fun register(sessionId: Int): SharedFlow<AppUpdateInstallState> {
        val flow = MutableSharedFlow<AppUpdateInstallState>(replay = 8, extraBufferCapacity = 8)
        sessionEvents[sessionId] = flow
        return flow.asSharedFlow()
    }

    fun unregister(sessionId: Int) {
        sessionEvents.remove(sessionId)
    }

    fun tryEmit(sessionId: Int, event: AppUpdateInstallState) {
        sessionEvents[sessionId]?.tryEmit(event)
        if (event is AppUpdateInstallState.Installed || event is AppUpdateInstallState.Error) {
            unregister(sessionId)
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.readArchiveInfo(filePath: String): PackageInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(filePath, PackageManager.PackageInfoFlags.of(0))
    } else {
        getPackageArchiveInfo(filePath, 0)
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        getParcelableExtra(key) as? Intent
    }
}

private fun Int.toInstallFailureMessage(systemMessage: String): String = when (this) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installazione annullata dall'utente."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android ha bloccato l'installazione dell'APK."
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflitto di firma o versione con l'app installata."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "L'APK non e' compatibile con questo dispositivo."
    PackageInstaller.STATUS_FAILURE_INVALID -> "Android considera l'APK non valido o non installabile."
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Spazio insufficiente per completare l'installazione."
    else -> systemMessage.ifBlank { "Installazione non riuscita." }
}

private object PendingIntentFlags {
    fun mutableUpdateCurrent(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}
