package com.keyvoice.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages all app preferences.
 * API key is stored securely via EncryptedSharedPreferences.
 * Other settings use standard SharedPreferences.
 */
class PreferencesManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "keyvoice_prefs"
        private const val ENCRYPTED_PREFS_NAME = "keyvoice_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "keyvoice_secure_fallback_prefs"

        private const val KEY_API_KEY = "groq_api_key"
        private const val KEY_LANGUAGE = "transcription_language"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_PHASE2_ENABLED = "phase2_enabled"
        private const val KEY_MAX_DURATION = "max_recording_duration"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_MANUAL_VOCABULARY = "manual_vocabulary"
        private const val KEY_LEARNED_VOCABULARY = "learned_vocabulary"
        private const val KEY_PROMPT_PRESET = "prompt_preset"
        private const val KEY_PREVIEW_LONG_TEXT_ENABLED = "preview_long_text_enabled"
        private const val MAX_VOCABULARY_TERMS = 250

        // Defaults
        const val DEFAULT_LANGUAGE = "it"
        const val DEFAULT_WHISPER_MODEL = "whisper-large-v3"
        const val DEFAULT_LLM_MODEL = "gpt-oss-20b"
        const val DEFAULT_PHASE2_ENABLED = true
        const val DEFAULT_MAX_DURATION = 180 // 3 minutes in seconds
        const val DEFAULT_HAPTIC_FEEDBACK = true
        const val DEFAULT_SYSTEM_PROMPT = PromptPreset.DEFAULT_CLEAN_PROMPT
        const val DEFAULT_VOCABULARY = ""
        const val DEFAULT_PREVIEW_LONG_TEXT_ENABLED = true

        // Language options
        const val LANGUAGE_ITALIAN = "it"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_AUTO = "auto"

        // Model options
        const val MODEL_GPT_OSS_20B = "gpt-oss-20b"
        const val MODEL_LLAMA_70B = "llama-3.3-70b-versatile"
        const val MODEL_LLAMA_8B = "llama3-8b-8192"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return runCatching {
            buildEncryptedPrefs()
        }.recoverCatching { firstError ->
            Log.w("PreferencesManager", "Encrypted preferences were unreadable; resetting secure prefs.", firstError)
            context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
            buildEncryptedPrefs()
        }.getOrElse { secondError ->
            Log.e("PreferencesManager", "Encrypted preferences unavailable; using fallback prefs.", secondError)
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Groq API Key - stored encrypted */
    var apiKey: String
        get() = runCatching {
            encryptedPrefs.getString(KEY_API_KEY, "") ?: ""
        }.getOrElse { error ->
            Log.w("PreferencesManager", "Unable to read stored API key; clearing secure prefs.", error)
            context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
            ""
        }
        set(value) {
            runCatching {
                encryptedPrefs.edit().putString(KEY_API_KEY, value).apply()
            }.onFailure { error ->
                Log.e("PreferencesManager", "Unable to write encrypted API key; using fallback prefs.", error)
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_API_KEY, value)
                    .apply()
            }
        }

    /** Transcription language ISO 639-1 code, or "auto" for auto-detect */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Whisper model identifier for Phase 1 transcription */
    var whisperModel: String
        get() = prefs.getString(KEY_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL
        set(value) = prefs.edit().putString(KEY_WHISPER_MODEL, value).apply()

    /** LLM model identifier for Phase 2 refinement */
    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(value) = prefs.edit().putString(KEY_LLM_MODEL, value).apply()

    /** Whether Phase 2 (LLM text refinement) is enabled */
    var isPhase2Enabled: Boolean
        get() = prefs.getBoolean(KEY_PHASE2_ENABLED, DEFAULT_PHASE2_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_PHASE2_ENABLED, value).apply()

    /** Maximum recording duration in seconds (30–600) */
    var maxRecordingDuration: Int
        get() = prefs.getInt(KEY_MAX_DURATION, DEFAULT_MAX_DURATION)
        set(value) = prefs.edit().putInt(KEY_MAX_DURATION, value.coerceIn(30, 600)).apply()

    /** Whether haptic feedback is enabled on mic tap */
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, DEFAULT_HAPTIC_FEEDBACK)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    /** Custom system prompt for Phase 2 */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    /** Legacy-compatible custom vocabulary, now backed by manual vocabulary entries. */
    var vocabulary: String
        get() = effectiveVocabulary
        set(value) {
            manualVocabulary = value
        }

    /** User-managed vocabulary entries. */
    var manualVocabulary: String
        get() {
            ensureVocabularyMigrated()
            return prefs.getString(KEY_MANUAL_VOCABULARY, DEFAULT_VOCABULARY) ?: DEFAULT_VOCABULARY
        }
        set(value) = prefs.edit()
            .putString(KEY_MANUAL_VOCABULARY, splitVocabulary(value).joinToString(", "))
            .remove(KEY_VOCABULARY)
            .apply()

    /** Automatically learned vocabulary entries. */
    var learnedVocabulary: String
        get() = prefs.getString(KEY_LEARNED_VOCABULARY, DEFAULT_VOCABULARY) ?: DEFAULT_VOCABULARY
        private set(value) = prefs.edit().putString(KEY_LEARNED_VOCABULARY, value).apply()

    val manualVocabularyTerms: List<String>
        get() = splitVocabulary(manualVocabulary)

    val learnedVocabularyTerms: List<String>
        get() = splitVocabulary(learnedVocabulary)

    val effectiveVocabulary: String
        get() = mergeVocabulary(manualVocabularyTerms, learnedVocabularyTerms).joinToString(", ")

    /** Selected prompt preset for Phase 2. */
    var promptPreset: PromptPreset
        get() {
            val stored = prefs.getString(KEY_PROMPT_PRESET, null)
            if (stored != null) return PromptPreset.fromId(stored)
            return if (systemPrompt != DEFAULT_SYSTEM_PROMPT) PromptPreset.CUSTOM else PromptPreset.CLEAN
        }
        set(value) = prefs.edit().putString(KEY_PROMPT_PRESET, value.id).apply()

    /** Whether long transcriptions should be previewed before insertion. */
    var previewLongTextEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_LONG_TEXT_ENABLED, DEFAULT_PREVIEW_LONG_TEXT_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW_LONG_TEXT_ENABLED, value).apply()

    /** Returns the system prompt resolved from the selected preset. */
    fun getResolvedSystemPrompt(): String {
        return promptPreset.systemPrompt ?: systemPrompt

    }

    /** Adds learned custom vocabulary terms while preserving existing manual entries. */
    fun addVocabularyTerms(terms: Collection<String>): List<String> {
        return addLearnedVocabularyTerms(terms)
    }

    fun addLearnedVocabularyTerms(terms: Collection<String>): List<String> {
        if (terms.isEmpty()) return emptyList()

        val existingTerms = learnedVocabularyTerms
        val manualKeys = manualVocabularyTerms
            .map { it.normalizedVocabularyKey() }
            .filter { it.isNotBlank() }
            .toSet()
        val existingKeys = existingTerms
            .map { it.normalizedVocabularyKey() }
            .filter { it.isNotBlank() }
            .toMutableSet()

        val additions = linkedSetOf<String>()
        terms.forEach { rawTerm ->
            val term = rawTerm.cleanVocabularyTerm()
            val key = term.normalizedVocabularyKey()
            if (term.isNotBlank() && key.isNotBlank() && key !in manualKeys && key !in existingKeys) {
                additions += term
                existingKeys += key
            }
        }

        if (additions.isEmpty()) return emptyList()

        val updatedTerms = (existingTerms + additions).takeLast(MAX_VOCABULARY_TERMS)
        learnedVocabulary = updatedTerms.joinToString(", ")
        return additions.toList()
    }

    fun removeLearnedVocabularyTerms(terms: Collection<String>) {
        if (terms.isEmpty()) return

        val removalKeys = terms.map { it.normalizedVocabularyKey() }.toSet()
        learnedVocabulary = learnedVocabularyTerms
            .filterNot { it.normalizedVocabularyKey() in removalKeys }
            .joinToString(", ")
    }

    fun clearLearnedVocabulary() {
        learnedVocabulary = DEFAULT_VOCABULARY
    }

    /** Returns the language display name for the configured language code */
    fun getLanguageDisplayName(): String {
        return when (language) {
            LANGUAGE_ITALIAN -> "Italiano"
            LANGUAGE_ENGLISH -> "English"
            LANGUAGE_AUTO -> "Auto-detect"
            else -> "Italiano"
        }
    }

    /** Returns the full language name for the system prompt */
    fun getLanguageFullName(): String {
        return when (language) {
            LANGUAGE_ITALIAN -> "italiano"
            LANGUAGE_ENGLISH -> "inglese"
            LANGUAGE_AUTO -> "rilevata automaticamente"
            else -> "italiano"
        }
    }

    /** Returns the language parameter for Whisper, or null for auto-detect */
    fun getWhisperLanguageParam(): String? {
        return if (language == LANGUAGE_AUTO) null else language
    }

    /** Check if API key is configured */
    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun splitVocabulary(value: String): List<String> {
        return value.split(',', ';', '\n')
            .map { it.cleanVocabularyTerm() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedVocabularyKey() }
    }

    private fun mergeVocabulary(manualTerms: List<String>, learnedTerms: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return (manualTerms + learnedTerms)
            .filter { seen.add(it.normalizedVocabularyKey()) }
            .takeLast(MAX_VOCABULARY_TERMS)
    }

    private fun ensureVocabularyMigrated() {
        if (prefs.contains(KEY_MANUAL_VOCABULARY)) return

        val legacy = prefs.getString(KEY_VOCABULARY, DEFAULT_VOCABULARY) ?: DEFAULT_VOCABULARY
        if (legacy.isNotBlank()) {
            prefs.edit()
                .putString(KEY_MANUAL_VOCABULARY, splitVocabulary(legacy).joinToString(", "))
                .remove(KEY_VOCABULARY)
                .apply()
        }
    }

    private fun String.cleanVocabularyTerm(): String {
        return trim().trim(',', ';')
    }

    private fun String.normalizedVocabularyKey(): String {
        return cleanVocabularyTerm().lowercase()
    }
}
