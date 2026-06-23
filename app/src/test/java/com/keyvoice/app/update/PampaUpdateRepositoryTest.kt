package com.keyvoice.app.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PampaUpdateRepositoryTest {

    @Test
    fun stableVersionComparatorHandlesEqualPatchMinorAndPrereleaseVersions() {
        assertFalse(isStableVersionNewer(candidate = "1.2.11", current = "1.2.11"))
        assertTrue(isStableVersionNewer(candidate = "1.2.12", current = "1.2.11"))
        assertTrue(isStableVersionNewer(candidate = "1.3.0", current = "1.2.11"))
        assertFalse(isStableVersionNewer(candidate = "1.3.0-beta.1", current = "1.2.11"))
    }

    @Test
    fun parserReadsStableManifestAndBuildsDownloadUrl() {
        val update = PampaUpdateParser.parseStableUpdate(manifest(version = "1.2.12"))

        assertEquals("1.2.12", update.version)
        assertEquals("Fix reliability", update.changelog)
        assertEquals("stable-keyvoice-v1.2.12", update.releaseTag)
        assertEquals("keyvoice-1.2.12.apk", update.apkAsset)
        assertEquals(
            "https://github.com/Casual76/KeyVoice/releases/download/stable-keyvoice-v1.2.12/keyvoice-1.2.12.apk",
            update.downloadUrl
        )
        assertEquals(42L, update.sizeBytes)
    }

    @Test
    fun automaticCheckRespectsIgnoredVersion() = runBlocking {
        val checker = checkerReturning(manifest(version = "1.2.12"))

        val update = checker.checkForStableUpdate(
            currentVersionName = "1.2.11",
            ignoredVersion = "1.2.12"
        ).getOrThrow()

        assertNull(update)
    }

    @Test
    fun manualCheckCanBypassIgnoredVersionByPassingEmptyIgnoredVersion() = runBlocking {
        val checker = checkerReturning(manifest(version = "1.2.12"))

        val update = checker.checkForStableUpdate(
            currentVersionName = "1.2.11",
            ignoredVersion = ""
        ).getOrThrow()

        assertEquals("1.2.12", update?.version)
    }

    @Test
    fun checkReturnsNullWhenRemoteVersionIsAlreadyInstalled() = runBlocking {
        val checker = checkerReturning(manifest(version = "1.2.11"))

        val update = checker.checkForStableUpdate(currentVersionName = "1.2.11").getOrThrow()

        assertNull(update)
    }

    @Test
    fun apkMetadataValidationReportsPackageAndVersionMismatch() {
        assertEquals(
            "L'APK scaricato non appartiene a questa app.",
            validateDownloadedApkMetadata(
                expectedPackageName = "com.keyvoice.app",
                expectedVersionName = "1.2.12",
                actualPackageName = "com.other.app",
                actualVersionName = "1.2.12"
            )
        )

        assertEquals(
            "Versione APK inattesa: 1.2.10.",
            validateDownloadedApkMetadata(
                expectedPackageName = "com.keyvoice.app",
                expectedVersionName = "1.2.12",
                actualPackageName = "com.keyvoice.app",
                actualVersionName = "1.2.10"
            )
        )
    }

    private fun checkerReturning(text: String): PampaUpdateChecker {
        return PampaUpdateChecker(
            manifestUrl = "https://example.test/manifest.json",
            manifestReader = { text }
        )
    }

    private fun manifest(version: String): String {
        return """
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
    }
}
