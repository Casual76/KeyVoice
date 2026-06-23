package com.keyvoice.app.update

import kotlinx.coroutines.flow.Flow

data class AvailableAppUpdate(
    val version: String,
    val changelog: String,
    val releaseTag: String,
    val apkAsset: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

sealed interface AppUpdateInstallState {
    data object Idle : AppUpdateInstallState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateInstallState
    data class Verifying(val message: String) : AppUpdateInstallState
    data class AwaitingUserAction(val message: String) : AppUpdateInstallState
    data class Installing(val message: String) : AppUpdateInstallState
    data class Installed(val filePath: String) : AppUpdateInstallState
    data class Error(val message: String) : AppUpdateInstallState
}

interface AppUpdateRepository {
    suspend fun checkForStableUpdate(
        currentVersionName: String,
        ignoredVersion: String = ""
    ): Result<AvailableAppUpdate?>

    fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState>
}

internal fun isStableVersionNewer(candidate: String, current: String): Boolean {
    if (candidate.contains("-")) return false
    return compareAppVersions(candidate, current) > 0
}

internal fun compareAppVersions(left: String, right: String): Int {
    val leftParts = left.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val rightParts = right.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val max = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until max) {
        val leftValue = leftParts.getOrElse(index) { 0 }
        val rightValue = rightParts.getOrElse(index) { 0 }
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }

    val leftPreRelease = left.substringAfter("-", "")
    val rightPreRelease = right.substringAfter("-", "")
    return when {
        leftPreRelease.isBlank() && rightPreRelease.isNotBlank() -> 1
        leftPreRelease.isNotBlank() && rightPreRelease.isBlank() -> -1
        else -> leftPreRelease.compareTo(rightPreRelease)
    }
}

internal fun validateDownloadedApkMetadata(
    expectedPackageName: String,
    expectedVersionName: String,
    actualPackageName: String?,
    actualVersionName: String?
): String? {
    if (actualPackageName != expectedPackageName) {
        return "L'APK scaricato non appartiene a questa app."
    }
    val actualVersion = actualVersionName.orEmpty()
    if (actualVersion.isNotBlank() && actualVersion != expectedVersionName) {
        return "Versione APK inattesa: $actualVersion."
    }
    return null
}
