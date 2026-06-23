package com.keyvoice.app

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
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
import com.google.android.material.textfield.TextInputLayout
import com.keyvoice.app.api.ApiKeyValidatorRepository
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.settings.PromptPreset
import com.keyvoice.app.update.AppUpdateInstallState
import com.keyvoice.app.update.AvailableAppUpdate
import com.keyvoice.app.update.KeyVoiceUpdateCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_UPDATE_CARD = "com.keyvoice.app.extra.SHOW_UPDATE_CARD"
    }

    private lateinit var prefs: PreferencesManager
    private val apiKeyValidator = ApiKeyValidatorRepository()
    private lateinit var updateCoordinator: KeyVoiceUpdateCoordinator
    private var availableUpdate: AvailableAppUpdate? = null
    private var updateInstallState: AppUpdateInstallState = AppUpdateInstallState.Idle
    private var updateCheckJob: Job? = null
    private var updateInstallJob: Job? = null

    // UI Elements
    private lateinit var setupWizardContainer: View
    private lateinit var tvWizardTitle: TextView
    private lateinit var tvWizardStatus: TextView
    private lateinit var tvStatusApiKey: TextView
    private lateinit var tvStatusKeyboard: TextView
    private lateinit var tvStatusAccessibility: TextView
    private lateinit var btnManageKeyboards: MaterialButton
    private lateinit var btnManageAccessibility: MaterialButton
    private lateinit var updateCard: View
    private lateinit var tvUpdateCurrentVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvUpdateChangelog: TextView
    private lateinit var updateProgressBar: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var btnInstallUpdate: MaterialButton
    private lateinit var btnLaterUpdate: MaterialButton
    private lateinit var btnIgnoreUpdate: MaterialButton
    private lateinit var layoutApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvApiLink: TextView
    private lateinit var btnTestApiKey: MaterialButton
    private lateinit var dropdownLanguage: AutoCompleteTextView
    private lateinit var dropdownWhisperModel: AutoCompleteTextView
    private lateinit var etVocabularyInput: TextInputEditText
    private lateinit var btnAddVocabulary: MaterialButton
    private lateinit var chipGroupManualVocabulary: ChipGroup
    private lateinit var switchAutoLearning: MaterialSwitch
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
    private lateinit var switchAccessibilityDictation: MaterialSwitch
    private lateinit var btnResetAccessibilityBubblePosition: MaterialButton
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
        updateCoordinator = KeyVoiceUpdateCoordinator(this, prefs)
        initViews()
        setupListeners()
        renderUpdateCard()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentSettings()
        updateSetupStatus()
        checkForUpdates(manual = intent?.getBooleanExtra(EXTRA_SHOW_UPDATE_CARD, false) == true)
        intent?.removeExtra(EXTRA_SHOW_UPDATE_CARD)
    }

    private fun initViews() {
        setupWizardContainer = findViewById(R.id.setup_wizard_container)
        tvWizardTitle = findViewById(R.id.tv_wizard_title)
        tvWizardStatus = findViewById(R.id.tv_wizard_status)
        tvStatusApiKey = findViewById(R.id.tv_status_api_key)
        tvStatusKeyboard = findViewById(R.id.tv_status_keyboard)
        tvStatusAccessibility = findViewById(R.id.tv_status_accessibility)
        btnManageKeyboards = findViewById(R.id.btn_manage_keyboards)
        btnManageAccessibility = findViewById(R.id.btn_manage_accessibility)
        updateCard = findViewById(R.id.update_card)
        tvUpdateCurrentVersion = findViewById(R.id.tv_update_current_version)
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        tvUpdateChangelog = findViewById(R.id.tv_update_changelog)
        updateProgressBar = findViewById(R.id.update_progress_bar)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnInstallUpdate = findViewById(R.id.btn_install_update)
        btnLaterUpdate = findViewById(R.id.btn_later_update)
        btnIgnoreUpdate = findViewById(R.id.btn_ignore_update)
        layoutApiKey = findViewById(R.id.layout_api_key)
        etApiKey = findViewById(R.id.et_api_key)
        tvApiLink = findViewById(R.id.tv_api_link)
        btnTestApiKey = findViewById(R.id.btn_test_api_key)
        dropdownLanguage = findViewById(R.id.dropdown_language)
        dropdownWhisperModel = findViewById(R.id.dropdown_whisper_model)
        etVocabularyInput = findViewById(R.id.et_vocabulary_input)
        btnAddVocabulary = findViewById(R.id.btn_add_vocabulary)
        chipGroupManualVocabulary = findViewById(R.id.chip_group_manual_vocabulary)
        switchAutoLearning = findViewById(R.id.switch_auto_learning)
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
        switchAccessibilityDictation = findViewById(R.id.switch_accessibility_dictation)
        btnResetAccessibilityBubblePosition = findViewById(R.id.btn_reset_accessibility_bubble_position)
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
        // Keep stored API keys out of the visible view tree; an empty field means "leave saved key unchanged".
        etApiKey.setText("")
        etApiKey.hint = null
        layoutApiKey.helperText = if (prefs.hasApiKey()) {
            getString(R.string.settings_api_key_saved_hint)
        } else {
            null
        }

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
        switchAutoLearning.isChecked = prefs.autoLearningEnabled
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
        switchAccessibilityDictation.isChecked = prefs.accessibilityDictationEnabled
    }

    private fun setupListeners() {
        btnManageKeyboards.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnManageAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupExpandableSections()

        tvApiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }

        btnTestApiKey.setOnClickListener {
            testApiKey()
        }

        btnCheckUpdate.setOnClickListener {
            checkForUpdates(manual = true)
        }

        btnInstallUpdate.setOnClickListener {
            startUpdateInstall()
        }

        btnLaterUpdate.setOnClickListener {
            availableUpdate = null
            updateInstallState = AppUpdateInstallState.Idle
            renderUpdateCard()
        }

        btnIgnoreUpdate.setOnClickListener {
            val update = availableUpdate ?: return@setOnClickListener
            updateCoordinator.ignoreVersion(update.version)
            availableUpdate = null
            updateInstallState = AppUpdateInstallState.Idle
            renderUpdateCard()
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

        btnResetAccessibilityBubblePosition.setOnClickListener {
            prefs.resetAccessibilityBubblePosition()
            Toast.makeText(
                this,
                getString(R.string.settings_accessibility_position_reset),
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheckJob?.isActive == true) return
        if (!manual &&
            System.currentTimeMillis() - prefs.lastAutomaticUpdateCheckMillis <
            KeyVoiceUpdateCoordinator.AUTOMATIC_CHECK_INTERVAL_MS
        ) {
            return
        }

        if (manual) {
            availableUpdate = null
            updateInstallState = AppUpdateInstallState.Idle
        }
        tvUpdateStatus.text = getString(R.string.update_status_checking)
        btnCheckUpdate.isEnabled = false

        updateCheckJob = lifecycleScope.launch {
            val result = updateCoordinator.checkForUpdate(
                manual = manual,
                notifyIfAvailable = true
            )
            btnCheckUpdate.isEnabled = true

            result.fold(
                onSuccess = { update ->
                    availableUpdate = update
                    updateInstallState = AppUpdateInstallState.Idle
                    renderUpdateCard(noUpdateMessage = manual && update == null)
                },
                onFailure = { error ->
                    availableUpdate = null
                    updateInstallState = AppUpdateInstallState.Error(
                        error.message ?: getString(R.string.update_status_error)
                    )
                    renderUpdateCard()
                    if (manual) {
                        Toast.makeText(
                            this@MainSetupActivity,
                            error.message ?: getString(R.string.update_status_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }

    private fun startUpdateInstall() {
        val update = availableUpdate ?: return
        if (updateInstallState.isBusy()) return

        updateInstallJob?.cancel()
        updateInstallJob = lifecycleScope.launch {
            updateCoordinator.install(update).collect { state ->
                updateInstallState = state
                renderUpdateCard()
            }
        }
    }

    private fun renderUpdateCard(noUpdateMessage: Boolean = false) {
        updateCard.visibility = View.VISIBLE
        tvUpdateCurrentVersion.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)

        val update = availableUpdate
        val installState = updateInstallState
        val busy = installState.isBusy()

        updateProgressBar.visibility = View.GONE
        tvUpdateChangelog.visibility = View.GONE
        btnInstallUpdate.visibility = View.GONE
        btnLaterUpdate.visibility = View.GONE
        btnIgnoreUpdate.visibility = View.GONE
        btnCheckUpdate.isEnabled = !busy

        when {
            update != null -> {
                tvUpdateStatus.text = when (installState) {
                    is AppUpdateInstallState.Downloading -> {
                        val percent = (installState.progress * 100).toInt().coerceIn(0, 100)
                        updateProgressBar.visibility = View.VISIBLE
                        updateProgressBar.progress = percent
                        getString(R.string.update_install_downloading, percent)
                    }
                    is AppUpdateInstallState.Verifying -> installState.message
                    is AppUpdateInstallState.AwaitingUserAction -> installState.message
                    is AppUpdateInstallState.Installing -> installState.message
                    is AppUpdateInstallState.Installed -> getString(R.string.update_install_installed)
                    is AppUpdateInstallState.Error -> installState.message
                    AppUpdateInstallState.Idle -> getString(R.string.update_status_available, update.version)
                }

                tvUpdateChangelog.text = update.changelog.ifBlank {
                    getString(R.string.update_status_available, update.version)
                }
                tvUpdateChangelog.visibility = View.VISIBLE
                btnInstallUpdate.visibility = View.VISIBLE
                btnInstallUpdate.isEnabled = !busy && installState !is AppUpdateInstallState.Installed
                btnLaterUpdate.visibility = View.VISIBLE
                btnIgnoreUpdate.visibility = View.VISIBLE
            }
            installState is AppUpdateInstallState.Error -> {
                tvUpdateStatus.text = installState.message
                btnCheckUpdate.isEnabled = true
            }
            noUpdateMessage -> {
                tvUpdateStatus.text = getString(R.string.update_status_none)
            }
            else -> {
                tvUpdateStatus.text = getString(R.string.update_status_idle)
            }
        }
    }

    private fun saveSettings() {
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isNotBlank()) {
            prefs.apiKey = apiKey
            etApiKey.setText("")
            etApiKey.hint = null
            layoutApiKey.helperText = getString(R.string.settings_api_key_saved_hint)
        }

        val langIdx = languageOptions.indexOf(dropdownLanguage.text.toString())
        if (langIdx >= 0) prefs.language = languageCodes[langIdx]

        prefs.whisperModel = dropdownWhisperModel.text.toString()
        prefs.manualVocabulary = manualVocabularyTerms.joinToString(", ")
        prefs.autoLearningEnabled = switchAutoLearning.isChecked

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
        prefs.accessibilityDictationEnabled = switchAccessibilityDictation.isChecked

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        updateSetupStatus()
    }

    private fun testApiKey() {
        val enteredApiKey = etApiKey.text?.toString()?.trim().orEmpty()
        val apiKey = enteredApiKey.ifBlank { prefs.apiKey }
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
                    if (enteredApiKey.isNotBlank()) {
                        prefs.apiKey = enteredApiKey
                        etApiKey.setText("")
                        etApiKey.hint = null
                        layoutApiKey.helperText = getString(R.string.settings_api_key_saved_hint)
                    }
                    Toast.makeText(
                        this@MainSetupActivity,
                        getString(R.string.settings_api_key_valid),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateSetupStatus()
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

    private fun setupExpandableSections() {
        setupExpandableSection(
            headerId = R.id.header_transcription,
            contentId = R.id.content_transcription,
            toggleId = R.id.tv_transcription_toggle,
            initiallyExpanded = false
        )
        setupExpandableSection(
            headerId = R.id.header_refinement,
            contentId = R.id.content_refinement,
            toggleId = R.id.tv_refinement_toggle,
            initiallyExpanded = false
        )
        setupExpandableSection(
            headerId = R.id.header_vocabulary,
            contentId = R.id.content_vocabulary,
            toggleId = R.id.tv_vocabulary_toggle,
            initiallyExpanded = false
        )
        setupExpandableSection(
            headerId = R.id.header_behavior,
            contentId = R.id.content_behavior,
            toggleId = R.id.tv_behavior_toggle,
            initiallyExpanded = true
        )
        setupExpandableSection(
            headerId = R.id.header_keyboard_fallback,
            contentId = R.id.content_keyboard_fallback,
            toggleId = R.id.tv_keyboard_fallback_toggle,
            initiallyExpanded = false
        )
    }

    private fun setupExpandableSection(
        headerId: Int,
        contentId: Int,
        toggleId: Int,
        initiallyExpanded: Boolean
    ) {
        val header = findViewById<View>(headerId)
        val content = findViewById<View>(contentId)
        val toggle = findViewById<TextView>(toggleId)
        var expanded = initiallyExpanded

        fun render() {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = getString(
                if (expanded) R.string.settings_section_hide else R.string.settings_section_show
            )
        }

        render()
        header.setOnClickListener {
            expanded = !expanded
            render()
        }
    }

    private fun updateSetupStatus() {
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

        val accessibilityEnabled = isKeyVoiceAccessibilityEnabled()
        if (accessibilityEnabled) {
            tvStatusAccessibility.text = getString(R.string.setup_accessibility_on)
            tvStatusAccessibility.setTextColor(getColor(R.color.success))
        } else {
            tvStatusAccessibility.text = getString(R.string.setup_accessibility_off)
            tvStatusAccessibility.setTextColor(getColor(R.color.error))
        }

        val hasApiKey = prefs.hasApiKey()
        if (hasApiKey) {
            tvStatusApiKey.text = getString(R.string.setup_api_key_ready)
            tvStatusApiKey.setTextColor(getColor(R.color.success))
        } else {
            tvStatusApiKey.text = getString(R.string.setup_api_key_missing)
            tvStatusApiKey.setTextColor(getColor(R.color.error))
        }

        val setupComplete = hasApiKey && accessibilityEnabled
        setupWizardContainer.visibility = View.VISIBLE
        tvWizardTitle.text = getString(
            if (setupComplete) R.string.setup_wizard_title_ready else R.string.setup_wizard_title
        )
        tvWizardStatus.text = when {
            !hasApiKey -> getString(R.string.setup_wizard_status_api_missing)
            !accessibilityEnabled -> getString(R.string.setup_wizard_status_accessibility_missing)
            else -> getString(R.string.setup_wizard_status_ready)
        }
    }

    private fun isKeyVoiceAccessibilityEnabled(): Boolean {
        val serviceId = ComponentName(
            packageName,
            "$packageName.accessibility.KeyVoiceAccessibilityService"
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(serviceId, ignoreCase = true) }
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

    private fun AppUpdateInstallState.isBusy(): Boolean = when (this) {
        is AppUpdateInstallState.Downloading,
        is AppUpdateInstallState.Verifying,
        is AppUpdateInstallState.Installing,
        is AppUpdateInstallState.AwaitingUserAction -> true
        AppUpdateInstallState.Idle,
        is AppUpdateInstallState.Installed,
        is AppUpdateInstallState.Error -> false
    }
}
