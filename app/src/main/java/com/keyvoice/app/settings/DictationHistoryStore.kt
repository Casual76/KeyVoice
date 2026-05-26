package com.keyvoice.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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

    private val gson = Gson()
    private val itemListType = object : TypeToken<List<DictationHistoryItem>>() {}.type

    private val prefs: SharedPreferences by lazy {
        createPrefs()
    }

    fun getItems(): List<DictationHistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]").orEmpty()
        return runCatching {
            gson.fromJson<List<DictationHistoryItem>>(raw, itemListType).orEmpty()
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read dictation history; clearing it.", error)
            clear()
            emptyList()
        }
    }

    fun add(item: DictationHistoryItem) {
        val updated = (listOf(item) + getItems().filterNot { it.id == item.id })
            .take(MAX_ITEMS)
        prefs.edit().putString(KEY_ITEMS, gson.toJson(updated)).apply()
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
}
