package com.keyvoice.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keyvoice.app.BuildConfig
import com.keyvoice.app.MainSetupActivity
import com.keyvoice.app.R
import com.keyvoice.app.settings.PreferencesManager

class KeyVoiceUpdateCoordinator(
    private val context: Context,
    private val prefs: PreferencesManager = PreferencesManager.getInstance(context),
    private val repository: AppUpdateRepository = PampaUpdateRepository(context)
) {
    suspend fun checkForUpdate(
        manual: Boolean = false,
        notifyIfAvailable: Boolean = true
    ): Result<AvailableAppUpdate?> {
        if (!manual && !prefs.shouldRunAutomaticUpdateCheck()) {
            return Result.success(null)
        }

        if (!manual) {
            prefs.lastAutomaticUpdateCheckMillis = System.currentTimeMillis()
        }

        val ignoredVersion = if (manual) "" else prefs.ignoredStableUpdateVersion
        return repository.checkForStableUpdate(
            currentVersionName = BuildConfig.VERSION_NAME,
            ignoredVersion = ignoredVersion
        ).onSuccess { update ->
            if (update != null && notifyIfAvailable) {
                showUpdateNotificationOnce(update)
            }
        }
    }

    fun ignoreVersion(version: String) {
        prefs.ignoredStableUpdateVersion = version
    }

    fun install(update: AvailableAppUpdate) = repository.install(update)

    private fun showUpdateNotificationOnce(update: AvailableAppUpdate) {
        if (prefs.notifiedStableUpdateVersion == update.version) return
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainSetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainSetupActivity.EXTRA_SHOW_UPDATE_CARD, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, update.version))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    update.changelog.ifBlank {
                        context.getString(R.string.update_notification_text, update.version)
                    }
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        prefs.notifiedStableUpdateVersion = update.version
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(UPDATE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
            }
        )
    }

    companion object {
        const val UPDATE_CHANNEL_ID = "keyvoice_updates"
        const val UPDATE_NOTIFICATION_ID = 1201
        const val AUTOMATIC_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}

private fun PreferencesManager.shouldRunAutomaticUpdateCheck(): Boolean {
    val elapsed = System.currentTimeMillis() - lastAutomaticUpdateCheckMillis
    return elapsed >= KeyVoiceUpdateCoordinator.AUTOMATIC_CHECK_INTERVAL_MS
}

private fun immutableFlag(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
