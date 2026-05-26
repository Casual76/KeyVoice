package com.keyvoice.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.keyvoice.app.api.ApiKeyValidatorRepository
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.settings.PromptPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainSetupActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private val apiKeyValidator = ApiKeyValidatorRepository()

    // UI Elements
    private lateinit var tvStatusKeyboard: TextView
    private lateinit var btnManageKeyboards: MaterialButton
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvApiLink: TextView
    private lateinit var btnTestApiKey: MaterialButton
    private lateinit var dropdownLanguage: AutoCompleteTextView
    private lateinit var dropdownWhisperModel: AutoCompleteTextView
    private lateinit var etVocabularyInput: TextInputEditText
    private lateinit var btnAddVocabulary: MaterialButton
    private lateinit var chipGroupManualVocabulary: ChipGroup
    private lateinit var chipGroupLearnedVocabulary: ChipGroup
    private lateinit var tvLearnedVocabularyEmpty: TextView
    private lateinit var btnClearLearnedVocabulary: MaterialButton
    private lateinit var switchPhase2: MaterialSwitch
    private lateinit var containerPhase2Settings: View
    private lateinit var dropdownPromptPreset: AutoCompleteTextView
    private lateinit var dropdownLlmModel: AutoCompleteTextView
    private lateinit var btnResetPrompt: MaterialButton
    private lateinit var containerPromptHeader: View
    private lateinit var layoutSystemPrompt: View
    private lateinit var etSystemPrompt: TextInputEditText
    private lateinit var sliderDuration: Slider
    private lateinit var tvDurationValue: TextView
    private lateinit var switchPreviewLongText: MaterialSwitch
    private lateinit var switchHaptic: MaterialSwitch
    private lateinit var switchReturnToPreviousKeyboard: MaterialSwitch
    private lateinit var switchAutoStartRecording: MaterialSwitch
    private lateinit var btnSave: MaterialButton
    private val manualVocabularyTerms = mutableListOf<String>()

    // Options mapping
    private val languageOptions = listOf("Italiano", "English", "Auto-detect")
    private val languageCodes = listOf(
        PreferencesManager.LANGUAGE_ITALIAN,
        PreferencesManager.LANGUAGE_ENGLISH,
        PreferencesManager.LANGUAGE_AUTO
    )

    private val whisperModelOptions = listOf(
        "whisper-large-v3",
        "whisper-large-v3-turbo"
    )

    private val llmModelOptions = listOf(
        PreferencesManager.MODEL_GPT_OSS_20B,
        PreferencesManager.MODEL_LLAMA_70B,
        PreferencesManager.MODEL_LLAMA_8B
    )

    private val promptPresetOptions = PromptPreset.entries.map { it.displayName }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_setup)
        prefs = PreferencesManager.getInstance(this)
        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateKeyboardStatus()
        loadCurrentSettings()
    }

    private fun initViews() {
        tvStatusKeyboard = findViewById(R.id.tv_status_keyboard)
        btnManageKeyboards = findViewById(R.id.btn_manage_keyboards)
        etApiKey = findViewById(R.id.et_api_key)
        tvApiLink = findViewById(R.id.tv_api_link)
        btnTestApiKey = findViewById(R.id.btn_test_api_key)
        dropdownLanguage = findViewById(R.id.dropdown_language)
        dropdownWhisperModel = findViewById(R.id.dropdown_whisper_model)
        etVocabularyInput = findViewById(R.id.et_vocabulary_input)
        btnAddVocabulary = findViewById(R.id.btn_add_vocabulary)
        chipGroupManualVocabulary = findViewById(R.id.chip_group_manual_vocabulary)
        chipGroupLearnedVocabulary = findViewById(R.id.chip_group_learned_vocabulary)
        tvLearnedVocabularyEmpty = findViewById(R.id.tv_learned_vocabulary_empty)
        btnClearLearnedVocabulary = findViewById(R.id.btn_clear_learned_vocabulary)
        switchPhase2 = findViewById(R.id.switch_phase2)
        containerPhase2Settings = findViewById(R.id.container_phase2_settings)
        dropdownPromptPreset = findViewById(R.id.dropdown_prompt_preset)
        dropdownLlmModel = findViewById(R.id.dropdown_model)
        btnResetPrompt = findViewById(R.id.tv_reset_prompt)
        containerPromptHeader = findViewById(R.id.container_prompt_header)
        layoutSystemPrompt = findViewById(R.id.layout_system_prompt)
        etSystemPrompt = findViewById(R.id.et_system_prompt)
        sliderDuration = findViewById(R.id.slider_duration)
        tvDurationValue = findViewById(R.id.tv_duration_value)
        switchPreviewLongText = findViewById(R.id.switch_preview_long_text)
        switchHaptic = findViewById(R.id.switch_haptic)
        switchReturnToPreviousKeyboard = findViewById(R.id.switch_return_to_previous_keyboard)
        switchAutoStartRecording = findViewById(R.id.switch_auto_start_recording)
        btnSave = findViewById(R.id.btn_save)

        // Adapters
        dropdownLanguage.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageOptions)
        )
        dropdownWhisperModel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, whisperModelOptions)
        )
        dropdownLlmModel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, llmModelOptions)
        )
        dropdownPromptPreset.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, promptPresetOptions)
        )
    }

    private fun loadCurrentSettings() {
        // API Key
        if (prefs.apiKey.isNotBlank()) etApiKey.setText(prefs.apiKey)

        // Language
        val langIdx = languageCodes.indexOf(prefs.language).coerceAtLeast(0)
        dropdownLanguage.setText(languageOptions[langIdx], false)

        // Whisper Model
        val wModelIdx = whisperModelOptions.indexOf(prefs.whisperModel).coerceAtLeast(0)
        dropdownWhisperModel.setText(whisperModelOptions[wModelIdx], false)

        // Vocabulary
        manualVocabularyTerms.clear()
        manualVocabularyTerms.addAll(prefs.manualVocabularyTerms)
        renderManualVocabularyChips()
        renderLearnedVocabularyChips()

        // Phase 2
        switchPhase2.isChecked = prefs.isPhase2Enabled
        containerPhase2Settings.visibility = if (prefs.isPhase2Enabled) View.VISIBLE else View.GONE

        dropdownPromptPreset.setText(prefs.promptPreset.displayName, false)
        updatePromptEditorVisibility()

        val lModelIdx = llmModelOptions.indexOf(prefs.llmModel).coerceAtLeast(0)
        dropdownLlmModel.setText(llmModelOptions[lModelIdx], false)

        etSystemPrompt.setText(prefs.systemPrompt)

        // Recording
        sliderDuration.value = prefs.maxRecordingDuration.toFloat()
        updateDurationLabel(prefs.maxRecordingDuration)
        switchPreviewLongText.isChecked = prefs.previewLongTextEnabled
        switchHaptic.isChecked = prefs.hapticFeedback
        switchReturnToPreviousKeyboard.isChecked = prefs.returnToPreviousKeyboard
        switchAutoStartRecording.isChecked = prefs.autoStartRecording
    }

    private fun setupListeners() {
        btnManageKeyboards.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        tvApiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }

        btnTestApiKey.setOnClickListener {
            testApiKey()
        }

        switchPhase2.setOnCheckedChangeListener { _, isChecked ->
            containerPhase2Settings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        dropdownPromptPreset.setOnItemClickListener { _, _, _, _ ->
            updatePromptEditorVisibility()
        }

        btnAddVocabulary.setOnClickListener {
            addManualVocabularyFromInput()
        }

        btnClearLearnedVocabulary.setOnClickListener {
            prefs.clearLearnedVocabulary()
            renderLearnedVocabularyChips()
        }

        btnResetPrompt.setOnClickListener {
            etSystemPrompt.setText(PreferencesManager.DEFAULT_SYSTEM_PROMPT)
            dropdownPromptPreset.setText(PromptPreset.CLEAN.displayName, false)
            updatePromptEditorVisibility()
        }

        sliderDuration.addOnChangeListener { _, value, _ ->
            updateDurationLabel(value.toInt())
        }

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isNotBlank()) prefs.apiKey = apiKey

        val langIdx = languageOptions.indexOf(dropdownLanguage.text.toString())
        if (langIdx >= 0) prefs.language = languageCodes[langIdx]

        prefs.whisperModel = dropdownWhisperModel.text.toString()
        prefs.manualVocabulary = manualVocabularyTerms.joinToString(", ")

        prefs.isPhase2Enabled = switchPhase2.isChecked
        val selectedPromptPreset = PromptPreset.fromDisplayName(dropdownPromptPreset.text.toString())
        prefs.promptPreset = selectedPromptPreset
        prefs.llmModel = dropdownLlmModel.text.toString()
        if (selectedPromptPreset == PromptPreset.CUSTOM) {
            prefs.systemPrompt = etSystemPrompt.text?.toString() ?: ""
        } else if (selectedPromptPreset == PromptPreset.CLEAN) {
            prefs.systemPrompt = PreferencesManager.DEFAULT_SYSTEM_PROMPT
        }

        prefs.maxRecordingDuration = sliderDuration.value.toInt()
        prefs.previewLongTextEnabled = switchPreviewLongText.isChecked
        prefs.hapticFeedback = switchHaptic.isChecked
        prefs.returnToPreviousKeyboard = switchReturnToPreviousKeyboard.isChecked
        prefs.autoStartRecording = switchAutoStartRecording.isChecked

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun testApiKey() {
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            Toast.makeText(this, getString(R.string.error_no_api_key), Toast.LENGTH_SHORT).show()
            return
        }

        btnTestApiKey.isEnabled = false
        btnTestApiKey.text = getString(R.string.settings_api_key_testing)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                apiKeyValidator.validate(apiKey)
            }

            btnTestApiKey.isEnabled = true
            btnTestApiKey.text = getString(R.string.settings_api_key_test)

            result.fold(
                onSuccess = {
                    prefs.apiKey = apiKey
                    Toast.makeText(
                        this@MainSetupActivity,
                        getString(R.string.settings_api_key_valid),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@MainSetupActivity,
                        error.message ?: getString(R.string.error_api_generic, 0),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun updateKeyboardStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledIds = imm.enabledInputMethodList.map { it.id }
        val isEnabled = enabledIds.any { it.contains(packageName) }

        if (isEnabled) {
            tvStatusKeyboard.text = getString(R.string.setup_step_done)
            tvStatusKeyboard.setTextColor(getColor(R.color.success))
        } else {
            tvStatusKeyboard.text = getString(R.string.setup_step_pending)
            tvStatusKeyboard.setTextColor(getColor(R.color.error))
        }
    }

    private fun updateDurationLabel(seconds: Int) {
        tvDurationValue.text = getString(R.string.settings_max_duration_value, seconds)
    }

    private fun updatePromptEditorVisibility() {
        val preset = PromptPreset.fromDisplayName(dropdownPromptPreset.text.toString())
        val isCustom = preset == PromptPreset.CUSTOM
        containerPromptHeader.visibility = if (isCustom) View.VISIBLE else View.GONE
        layoutSystemPrompt.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun addManualVocabularyFromInput() {
        val terms = prefs.splitVocabulary(etVocabularyInput.text?.toString().orEmpty())
        if (terms.isEmpty()) return

        val existing = manualVocabularyTerms
            .map { it.lowercase() }
            .toMutableSet()
        terms.forEach { term ->
            if (existing.add(term.lowercase())) {
                manualVocabularyTerms += term
            }
        }
        etVocabularyInput.setText("")
        renderManualVocabularyChips()
    }

    private fun renderManualVocabularyChips() {
        chipGroupManualVocabulary.removeAllViews()
        manualVocabularyTerms.forEach { term ->
            chipGroupManualVocabulary.addView(createVocabularyChip(term) {
                manualVocabularyTerms.remove(term)
                renderManualVocabularyChips()
            })
        }
    }

    private fun renderLearnedVocabularyChips() {
        val learnedTerms = prefs.learnedVocabularyTerms
        chipGroupLearnedVocabulary.removeAllViews()
        tvLearnedVocabularyEmpty.visibility = if (learnedTerms.isEmpty()) View.VISIBLE else View.GONE
        btnClearLearnedVocabulary.visibility = if (learnedTerms.isEmpty()) View.GONE else View.VISIBLE

        learnedTerms.forEach { term ->
            chipGroupLearnedVocabulary.addView(createVocabularyChip(term) {
                prefs.removeLearnedVocabularyTerms(listOf(term))
                renderLearnedVocabularyChips()
            })
        }
    }

    private fun createVocabularyChip(term: String, onRemove: () -> Unit): Chip {
        return Chip(this).apply {
            text = term
            isCloseIconVisible = true
            setOnCloseIconClickListener { onRemove() }
        }
    }
}
