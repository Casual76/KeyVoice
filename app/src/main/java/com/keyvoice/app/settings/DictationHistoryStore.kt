package com.keyvoice.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class DictationHistoryItem(
    val id: String,
    val createdAtMillis: Long,
    val rawText: String,
    val finalText: String,
    val insertedText: String,
    val promptPreset: String,
    val phase2Used: Boolean
)

class DictationHistoryStore(private val context: Context) {

    companion object {
        private const val TAG = "DictationHistoryStore"
        private const val PREFS_NAME = "keyvoice_history_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "keyvoice_history_fallback_prefs"
        private const val KEY_ITEMS = "dictation_history"
        private const val MAX_ITEMS = 10
    }

    private val prefs: SharedPreferences by lazy {
        createPrefs()
    }

    fun getItems(): List<DictationHistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]").orEmpty()
        return runCatching {
            parseItems(raw)
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read dictation history; clearing it.", error)
            clear()
            emptyList()
        }
    }

    fun add(item: DictationHistoryItem) {
        val updated = (listOf(item) + getItems().filterNot { it.id == item.id })
            .take(MAX_ITEMS)
        prefs.edit().putString(KEY_ITEMS, serializeItems(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    private fun createPrefs(): SharedPreferences {
        return runCatching {
            buildEncryptedPrefs()
        }.getOrElse { error ->
            Log.w(TAG, "Encrypted history unavailable; using private fallback prefs.", error)
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun parseItems(raw: String): List<DictationHistoryItem> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    DictationHistoryItem(
                        id = item.optString("id"),
                        createdAtMillis = item.optLong("createdAtMillis"),
                        rawText = item.optString("rawText"),
                        finalText = item.optString("finalText"),
                        insertedText = item.optString("insertedText"),
                        promptPreset = item.optString("promptPreset"),
                        phase2Used = item.optBoolean("phase2Used")
                    )
                )
            }
        }
    }

    private fun serializeItems(items: List<DictationHistoryItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("createdAtMillis", item.createdAtMillis)
                    .put("rawText", item.rawText)
                    .put("finalText", item.finalText)
                    .put("insertedText", item.insertedText)
                    .put("promptPreset", item.promptPreset)
                    .put("phase2Used", item.phase2Used)
            )
        }
        return array.toString()
    }
}
