package com.keyvoice.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.keyvoice.app.accessibility.KeyVoiceAccessibilityService
import com.keyvoice.app.api.ApiKeyValidatorRepository
import com.keyvoice.app.api.GroqModelCatalog
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.settings.PromptPreset
import com.keyvoice.app.ui.setup.KeyVoiceSetupScreen
import com.keyvoice.app.update.KeyVoiceUpdateCoordinator
import com.keyvoice.app.update.updateStateAfterCheck
import com.keyvoice.app.update.updateStateAfterFailedCheck
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.isBusy
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateState
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainSetupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SHOW_UPDATE_CARD = "com.keyvoice.app.extra.SHOW_UPDATE_CARD"
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var ui: SetupState
    private lateinit var updates: KeyVoiceUpdateCoordinator
    private val validator = ApiKeyValidatorRepository()
    private var updateCheckJob: Job? = null
    private var updateInstallJob: Job? = null
    private var modelRefreshJob: Job? = null
    private var apiTestJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferencesManager.getInstance(this)
        ui = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SetupState(prefs) as T
        })[SetupState::class.java]
        updates = KeyVoiceUpdateCoordinator(this, prefs)
        // Le coroutine dell'attivita' muoiono con lei (anche con una rotazione), lo stato nel ViewModel no:
        // un controllo o uno scaricamento rimasto a meta' non ha piu' nessuno che lo porti avanti, e
        // lasciarlo "in corso" bloccherebbe per sempre il tasto che lo ripete.
        if (ui.updateState.isBusy) {
            ui.updateState = ui.availableUpdate
                ?.let { FluidUpdateUiState.Available(it.toFluidUpdateRelease()) }
                ?: FluidUpdateUiState.Idle
        }
        setContent {
            FluidTheme(
                settings = EngineSettings(hapticsEnabled = ui.haptic),
                brand = AccentPreset(
                    name = "keyvoice",
                    label = "KeyVoice",
                    light = Color(0xFF6259DF),
                    dark = Color(0xFFA9A2FF),
                ),
            ) {
                KeyVoiceSetupScreen(
                    state = ui,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenKeyboards = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onOpenGroq = {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
                    },
                    onTestApiKey = ::testApiKey,
                    onSave = ::saveSettings,
                    onCheckUpdate = { checkForUpdates(manual = true) },
                    onInstallUpdate = ::startUpdateInstall,
                    // "Piu' tardi" chiude solo l'avviso a foglio: la sezione nelle impostazioni resta e
                    // continua a offrire l'installazione. "Salta" e' invece per sempre, per quella versione.
                    onLaterUpdate = { ui.updatePromptRequested = false },
                    onIgnoreUpdate = {
                        ui.availableUpdate?.let { updates.ignoreVersion(it.version) }
                        ui.availableUpdate = null
                        ui.updateState = FluidUpdateUiState.Idle
                        ui.updatePromptRequested = false
                    },
                    onResetBubble = {
                        prefs.resetAccessibilityBubblePosition()
                        toast(R.string.settings_accessibility_position_reset)
                    },
                    onClearLearned = {
                        prefs.clearLearnedVocabulary()
                        ui.learnedTerms.clear()
                    },
                    onRemoveLearned = { term ->
                        prefs.removeLearnedVocabularyTerms(listOf(term))
                        ui.learnedTerms.clear()
                        ui.learnedTerms.addAll(prefs.learnedVocabularyTerms)
                    },
                    onAddVocabulary = ::addVocabulary,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        refreshSetupStatus()
        refreshGroqModels()
        // Dalla notifica si arriva per l'aggiornamento: il controllo e' esplicito e, se c'e' una
        // novita', si apre l'avviso a foglio invece di lasciare la persona in cima alla dashboard.
        val fromNotification = intent?.getBooleanExtra(EXTRA_SHOW_UPDATE_CARD, false) == true
        checkForUpdates(manual = fromNotification, promptIfAvailable = fromNotification)
        intent?.removeExtra(EXTRA_SHOW_UPDATE_CARD)
    }

    private fun refreshSetupStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        ui.keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val service = ComponentName(this, KeyVoiceAccessibilityService::class.java).flattenToString()
        ui.accessibilityEnabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { it.equals(service, ignoreCase = true) }
        ui.hasApiKey = prefs.hasApiKey()
    }

    private fun addVocabulary() {
        val seen = ui.manualTerms.map { it.lowercase() }.toMutableSet()
        prefs.splitVocabulary(ui.vocabularyInput).forEach { term ->
            if (seen.add(term.lowercase())) ui.manualTerms.add(term)
        }
        ui.vocabularyInput = ""
    }

    private fun saveSettings() {
        // The global Save action includes a term still typed into the vocabulary field.
        if (ui.vocabularyInput.isNotBlank()) addVocabulary()
        val enteredKey = ui.apiKeyInput.trim()
        val cancelledKeyTest = apiTestJob?.isActive == true
        if (cancelledKeyTest) {
            apiTestJob?.cancel()
            ui.testingApiKey = false
        }
        if (enteredKey.isNotEmpty()) {
            modelRefreshJob?.cancel()
            try {
                prefs.apiKey = enteredKey
            } catch (error: Exception) {
                Log.e("KeyVoiceSettings", "Unable to save API key securely", error)
                toast(R.string.settings_api_key_save_failed)
                return
            }
            ui.apiKeyInput = ""
            ui.hasApiKey = prefs.hasApiKey()
        }
        prefs.language = ui.language
        prefs.whisperModel = ui.whisperModel.ifBlank { PreferencesManager.DEFAULT_WHISPER_MODEL }
        prefs.manualVocabulary = ui.manualTerms.joinToString(", ")
        prefs.autoLearningEnabled = ui.autoLearning
        prefs.isPhase2Enabled = ui.phase2
        prefs.promptPreset = ui.preset
        prefs.llmModel = ui.llmModel.ifBlank { PreferencesManager.DEFAULT_LLM_MODEL }
        prefs.systemPrompt = when (ui.preset) {
            PromptPreset.CUSTOM -> ui.systemPrompt
            PromptPreset.CLEAN -> PreferencesManager.DEFAULT_SYSTEM_PROMPT
            else -> prefs.systemPrompt
        }
        prefs.maxRecordingDuration = ui.duration
        prefs.previewLongTextEnabled = ui.previewLongText
        prefs.hapticFeedback = ui.haptic
        prefs.returnToPreviousKeyboard = ui.returnToPreviousKeyboard
        prefs.autoStartRecording = ui.autoStartRecording
        prefs.accessibilityDictationEnabled = ui.accessibilityDictation
        toast(R.string.settings_saved)
        if (enteredKey.isNotEmpty() || cancelledKeyTest) refreshGroqModels()
    }

    private fun refreshGroqModels() {
        val key = prefs.apiKey
        if (key.isBlank() || modelRefreshJob?.isActive == true) return
        ui.modelStatus = getString(R.string.settings_models_loading)
        modelRefreshJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { validator.fetchModelCatalog(key) }
            result.fold(
                onSuccess = { applyModelCatalog(it, migrateSavedModels = true) },
                onFailure = { ui.modelStatus = getString(R.string.settings_models_fallback) },
            )
        }
    }

    private fun applyModelCatalog(catalog: GroqModelCatalog, migrateSavedModels: Boolean) {
        val selection = catalog.resolveSelection(
            draftWhisper = ui.whisperModel,
            draftLlm = ui.llmModel,
            savedWhisper = prefs.whisperModel,
            savedLlm = prefs.llmModel,
            catalogBelongsToSavedKey = migrateSavedModels,
        )
        ui.whisperOptions.clear()
        ui.whisperOptions.addAll(catalog.transcriptionModels)
        ui.llmOptions.clear()
        ui.llmOptions.addAll(catalog.llmModels)
        ui.whisperModel = selection.draftWhisper
        ui.llmModel = selection.draftLlm
        // Keep dictation usable if Groq retires a persisted model. A catalog fetched with a
        // proposed new key must not silently change settings still used by the saved key.
        selection.savedWhisperReplacement?.let { prefs.whisperModel = it }
        selection.savedLlmReplacement?.let { prefs.llmModel = it }
        ui.modelStatus = getString(R.string.settings_models_live)
    }

    private fun testApiKey() {
        if (apiTestJob?.isActive == true) return
        val enteredKey = ui.apiKeyInput.trim()
        val key = enteredKey.ifBlank { prefs.apiKey }
        if (key.isBlank()) {
            toast(R.string.error_no_api_key)
            return
        }
        modelRefreshJob?.cancel()
        ui.testingApiKey = true
        ui.modelStatus = getString(R.string.settings_models_loading)
        apiTestJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { validator.fetchModelCatalog(key) }
            ui.testingApiKey = false
            result.fold(
                onSuccess = { catalog ->
                    applyModelCatalog(catalog, migrateSavedModels = enteredKey.isBlank())
                    toast(R.string.settings_api_key_valid)
                },
                onFailure = { error ->
                    ui.modelStatus = getString(R.string.settings_models_fallback)
                    Toast.makeText(this@MainSetupActivity, error.message ?: getString(R.string.update_status_error), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun checkForUpdates(manual: Boolean, promptIfAvailable: Boolean = false) {
        if (updateCheckJob?.isActive == true) return
        // Un controllo o un'installazione in corso non si interrompe: tornare dalla finestra di
        // conferma di Android riapre l'attivita', e ripartire da qui azzererebbe la barra.
        if (ui.updateState.isBusy) return
        if (!manual && System.currentTimeMillis() - prefs.lastAutomaticUpdateCheckMillis <
            KeyVoiceUpdateCoordinator.AUTOMATIC_CHECK_INTERVAL_MS) return
        if (manual) ui.availableUpdate = null
        ui.updateState = FluidUpdateUiState.Checking
        updateCheckJob = lifecycleScope.launch {
            val result = updates.checkForUpdate(manual = manual, notifyIfAvailable = true)
            result.fold(
                onSuccess = { update ->
                    ui.availableUpdate = update
                    ui.updateState = updateStateAfterCheck(update, manual)
                    ui.lastUpdateCheckMillis = System.currentTimeMillis()
                    ui.updatePromptRequested = promptIfAvailable && update != null
                },
                onFailure = { error ->
                    ui.availableUpdate = null
                    Log.w("KeyVoiceUpdates", "Update check failed", error)
                    val message = getString(R.string.update_status_error)
                    ui.updateState = updateStateAfterFailedCheck(message)
                    if (manual) Toast.makeText(this@MainSetupActivity, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun startUpdateInstall() {
        val update = ui.availableUpdate ?: return
        if (ui.updateState.isBusy) return
        val release = update.toFluidUpdateRelease()
        updateInstallJob?.cancel()
        updateInstallJob = lifecycleScope.launch {
            updates.install(update).collect { ui.updateState = it.toFluidUpdateState(release) }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}

internal class SetupState(prefs: PreferencesManager) : ViewModel() {
    var apiKeyInput by mutableStateOf("")
    var hasApiKey by mutableStateOf(prefs.hasApiKey())
    var keyboardEnabled by mutableStateOf(false)
    var accessibilityEnabled by mutableStateOf(false)
    var language by mutableStateOf(prefs.language)
    var whisperModel by mutableStateOf(prefs.whisperModel)
    var llmModel by mutableStateOf(prefs.llmModel)
    val whisperOptions = mutableStateListOf<String>().apply { addAll(GroqModelCatalog.FALLBACK.transcriptionModels) }
    val llmOptions = mutableStateListOf<String>().apply { addAll(GroqModelCatalog.FALLBACK.llmModels) }
    var modelStatus by mutableStateOf("")
    var testingApiKey by mutableStateOf(false)
    var vocabularyInput by mutableStateOf("")
    val manualTerms = mutableStateListOf<String>().apply { addAll(prefs.manualVocabularyTerms) }
    val learnedTerms = mutableStateListOf<String>().apply { addAll(prefs.learnedVocabularyTerms) }
    var autoLearning by mutableStateOf(prefs.autoLearningEnabled)
    var phase2 by mutableStateOf(prefs.isPhase2Enabled)
    var preset by mutableStateOf(prefs.promptPreset)
    var systemPrompt by mutableStateOf(prefs.systemPrompt)
    var duration by mutableStateOf(prefs.maxRecordingDuration)
    var previewLongText by mutableStateOf(prefs.previewLongTextEnabled)
    var haptic by mutableStateOf(prefs.hapticFeedback)
    var returnToPreviousKeyboard by mutableStateOf(prefs.returnToPreviousKeyboard)
    var autoStartRecording by mutableStateOf(prefs.autoStartRecording)
    var accessibilityDictation by mutableStateOf(prefs.accessibilityDictationEnabled)
    /** La novita' trovata, per installarla; quello che si vede e' [updateState]. */
    var availableUpdate by mutableStateOf<AvailableAppUpdate?>(null)
    var updateState by mutableStateOf<FluidUpdateUiState>(FluidUpdateUiState.Idle)
    var lastUpdateCheckMillis by mutableStateOf(prefs.lastAutomaticUpdateCheckMillis.takeIf { it > 0L })
    /** Vero se la persona e' arrivata dalla notifica: allora l'avviso si apre come foglio. */
    var updatePromptRequested by mutableStateOf(false)
}
