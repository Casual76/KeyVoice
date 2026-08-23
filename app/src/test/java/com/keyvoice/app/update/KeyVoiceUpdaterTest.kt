package com.keyvoice.app.update

import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.foundation.isStableVersionNewer
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gli stessi casi che coprivano l'updater scritto a mano, ora contro quello dell'engine.
 *
 * Sono rimasti qui invece di finire nell'engine perche' non verificano l'engine: verificano che
 * *questa* app legga *il suo* manifest e ne ricavi l'URL da cui si scarica davvero. Se un giorno il
 * formato del manifest del Pampa Store cambia, e' questo file a dirlo.
 */
class KeyVoiceUpdaterTest {

    @Test
    fun stableVersionComparatorHandlesEqualPatchMinorAndPrereleaseVersions() {
        assertFalse(isStableVersionNewer(candidate = "1.2.11", current = "1.2.11"))
        assertTrue(isStableVersionNewer(candidate = "1.2.12", current = "1.2.11"))
        assertTrue(isStableVersionNewer(candidate = "1.3.0", current = "1.2.11"))
        assertFalse(isStableVersionNewer(candidate = "1.3.0-beta.1", current = "1.2.11"))
    }

    @Test
    fun readsStableManifestAndBuildsDownloadUrl() = runBlocking {
        val update = updaterReturning(manifest(version = "1.2.12"))
            .check(currentVersionName = "1.2.11")
            .getOrThrow()

        assertEquals("1.2.12", update?.version)
        assertEquals("Fix reliability", update?.changelog)
        assertEquals("stable-keyvoice-v1.2.12", update?.releaseTag)
        assertEquals("keyvoice-1.2.12.apk", update?.apkAsset)
        assertEquals(
            "https://github.com/Casual76/KeyVoice/releases/download/stable-keyvoice-v1.2.12/keyvoice-1.2.12.apk",
            update?.downloadUrl
        )
        assertEquals(42L, update?.sizeBytes)
    }

    @Test
    fun automaticCheckRespectsIgnoredVersion() = runBlocking {
        val update = updaterReturning(manifest(version = "1.2.12"))
            .check(currentVersionName = "1.2.11", ignoredVersion = "1.2.12")
            .getOrThrow()

        assertNull(update)
    }

    @Test
    fun manualCheckCanBypassIgnoredVersionByPassingEmptyIgnoredVersion() = runBlocking {
        val update = updaterReturning(manifest(version = "1.2.12"))
            .check(currentVersionName = "1.2.11", ignoredVersion = "")
            .getOrThrow()

        assertEquals("1.2.12", update?.version)
    }

    @Test
    fun checkReturnsNullWhenRemoteVersionIsAlreadyInstalled() = runBlocking {
        val update = updaterReturning(manifest(version = "1.2.11"))
            .check(currentVersionName = "1.2.11")
            .getOrThrow()

        assertNull(update)
    }

    /**
     * Il manifest di KeyVoice pubblica `beta` uguale a `stable`, e senza questo controllo un'app sul
     * canale beta continuerebbe a vedersi offrire la versione che ha gia' installato.
     */
    @Test
    fun betaChannelDoesNotOfferTheVersionAlreadyInstalled() = runBlocking {
        val update = updaterReturning(manifest(version = "1.2.11"))
            .check(currentVersionName = "1.2.11", channel = UpdateChannel.BETA)
            .getOrThrow()

        assertNull(update)
    }

    @Test
    fun checkFailsInsteadOfPretendingThereIsNothingNewWhenTheManifestIsUnreachable() = runBlocking {
        val updater = EngineAppUpdater(
            http = object : EngineHttp() {
                override suspend fun readText(url: String, headers: Map<String, String>): String =
                    error("Richiesta non riuscita (404).")
            },
            source = UpdateSource(manifestUrl = MANIFEST_URL, applicationId = PACKAGE_NAME),
            installer = NoInstaller,
        )

        assertTrue(updater.check(currentVersionName = "1.2.11").isFailure)
    }

    private fun updaterReturning(text: String): EngineAppUpdater = EngineAppUpdater(
        http = object : EngineHttp() {
            override suspend fun readText(url: String, headers: Map<String, String>): String {
                assertEquals(MANIFEST_URL, url)
                return text
            }
        },
        source = UpdateSource(manifestUrl = MANIFEST_URL, applicationId = PACKAGE_NAME),
        installer = NoInstaller,
    )

    private object NoInstaller : AppUpdateInstaller {
        override fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> = emptyFlow()
    }

    private fun manifest(version: String): String = """
        {
          "app": {
            "id": "keyvoice",
            "name": "KeyVoice",
            "packageName": "com.keyvoice.app",
            "repository": {
              "repoOwner": "Casual76",
              "repoName": "KeyVoice",
              "manifestPath": "manifest.json",
              "ref": ""
            },
            "stable": {
              "version": "$version",
              "releaseDate": "2026-06-23",
              "changelog": "Fix reliability",
              "releaseTag": "stable-keyvoice-v$version",
              "apkAsset": "keyvoice-$version.apk",
              "exeAsset": "",
              "sizeBytes": 42
            }
          }
        }
    """.trimIndent()

    private companion object {
        const val MANIFEST_URL = KEYVOICE_MANIFEST_URL
        const val PACKAGE_NAME = "com.keyvoice.app"
    }
}
