package com.keyvoice.app.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecureStorageMigrationTest {
    private val testPrefix = "qa_secure_migration_"

    // Keep the tests in their own SharedPreferences files inside the target process.
    private val context: Context = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(testPrefix + name, mode)

        override fun deleteSharedPreferences(name: String): Boolean =
            super.deleteSharedPreferences(testPrefix + name)
    }

    @After
    fun cleanTestData() {
        listOf(
            "keyvoice_secure_fallback_prefs",
            "keyvoice_secure_prefs",
            "keyvoice_history_fallback_prefs",
            "keyvoice_history_secure_prefs",
            "keyvoice_prefs",
        ).forEach(context::deleteSharedPreferences)
    }

    @Test
    fun legacyApiKeyMovesToEncryptedStorageAndPlaintextFileIsRemoved() {
        val dummy = "KV_QA_ONLY_MIGRATION_TEST"
        assertTrue(context.getSharedPreferences("keyvoice_secure_fallback_prefs", Context.MODE_PRIVATE)
            .edit().putString("groq_api_key", dummy).commit())

        val preferences = PreferencesManager.getInstance(context)

        assertEquals(dummy, preferences.apiKey)
        assertFalse(preferencesFile("keyvoice_secure_fallback_prefs").exists())
        val encryptedFile = preferencesFile("keyvoice_secure_prefs")
        assertTrue(encryptedFile.exists())
        assertFalse(encryptedFile.readText().contains(dummy))
    }

    @Test
    fun legacyHistoryMovesToEncryptedStorageAndPlaintextFileIsRemoved() {
        val dummy = "KV_QA_ONLY_HISTORY_TEST"
        val history = """[{"id":"qa-1","createdAtMillis":1,"rawText":"$dummy","finalText":"$dummy","insertedText":"$dummy","promptPreset":"clean","phase2Used":false}]"""
        assertTrue(context.getSharedPreferences("keyvoice_history_fallback_prefs", Context.MODE_PRIVATE)
            .edit().putString("dictation_history", history).commit())

        val items = DictationHistoryStore(context).getItems()

        assertEquals(dummy, items.single().insertedText)
        assertFalse(preferencesFile("keyvoice_history_fallback_prefs").exists())
        val encryptedFile = preferencesFile("keyvoice_history_secure_prefs")
        assertTrue(encryptedFile.exists())
        assertFalse(encryptedFile.readText().contains(dummy))
    }

    private fun preferencesFile(name: String): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$testPrefix$name.xml")
}
