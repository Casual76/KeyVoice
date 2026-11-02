package com.keyvoice.app.ui.setup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keyvoice.app.BuildConfig
import com.keyvoice.app.R
import com.keyvoice.app.SetupState
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.settings.PromptPreset
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSheetScaffold
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.fluid.FluidUpdatePromptSheet
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidItemSpacing
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone

@Composable
internal fun KeyVoiceSetupScreen(
    state: SetupState,
    onOpenAccessibility: () -> Unit,
    onOpenKeyboards: () -> Unit,
    onOpenGroq: () -> Unit,
    onTestApiKey: () -> Unit,
    onSave: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onLaterUpdate: () -> Unit,
    onIgnoreUpdate: () -> Unit,
    onResetBubble: () -> Unit,
    onClearLearned: () -> Unit,
    onRemoveLearned: (String) -> Unit,
    onAddVocabulary: () -> Unit,
) {
    val ready = state.hasApiKey && state.accessibilityEnabled
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val modalHost = rememberFluidGlassModalHostState()
    var vocabularySheet by remember { mutableStateOf<VocabularySheet?>(null) }
    CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
    FluidScreen(
        title = stringResource(R.string.setup_welcome),
        subtitle = stringResource(R.string.setup_subtitle_fluid),
        ambient = FluidAmbient(FluidHeroTone.PrimaryToSecondary, FluidHeroMotif.Ripples),
        overlay = { backdrop -> FluidGlassModalHost(state = modalHost, backdrop = backdrop) },
        actions = {
            FluidBarAction(
                icon = Icons.Rounded.Save,
                contentDescription = stringResource(R.string.settings_save),
                onClick = onSave,
            )
        },
    ) {
        // Lo stato della configurazione: una frase con l'icona del microfono nella piastrella.
        // Era un FluidHeroCard (deprecato dalla 2.13): quello era un riquadro che dice una cosa, e
        // questo e' il suo successore. Il tono e' quello primario, come l'accento del vecchio riquadro:
        // verde per "pronto" e ambra per "manca qualcosa" sarebbe una scelta di aspetto (vedi report).
        item(key = "hero") {
            FluidInlineMessage(
                title = stringResource(if (ready) R.string.setup_wizard_title_ready else R.string.setup_wizard_title),
                message = stringResource(
                    when {
                        !state.hasApiKey -> R.string.setup_wizard_status_api_missing
                        !state.accessibilityEnabled -> R.string.setup_wizard_status_accessibility_missing
                        else -> R.string.setup_wizard_status_ready
                    }
                ),
                tone = FluidTone.Primary,
                leading = { Icon(Icons.Rounded.Mic, contentDescription = null) },
            )
        }
        item(key = "setup-header") { FluidSectionHeader(stringResource(R.string.setup_quick_start)) }
        item(key = "setup") {
            FluidListGroup(glass = true) {
                FluidListRow(
                    title = stringResource(R.string.setup_accessibility_title),
                    subtitle = if (largeText) {
                        stringResource(if (state.accessibilityEnabled) R.string.setup_status_active else R.string.setup_status_to_enable) +
                            " · " + stringResource(R.string.setup_accessibility_short)
                    } else stringResource(R.string.setup_accessibility_short),
                    tone = if (state.accessibilityEnabled) FluidTone.Success else FluidTone.Warning,
                    badge = if (largeText) null else ({
                        FluidStatusBadge(
                            label = stringResource(if (state.accessibilityEnabled) R.string.setup_status_active else R.string.setup_status_to_enable),
                            tone = if (state.accessibilityEnabled) FluidTone.Success else FluidTone.Warning,
                        )
                    }),
                    onClick = onOpenAccessibility,
                )
                FluidListDivider()
                FluidListRow(
                    title = stringResource(R.string.setup_step1_title),
                    subtitle = if (largeText) {
                        stringResource(if (state.keyboardEnabled) R.string.setup_status_active else R.string.setup_status_to_enable) +
                            " · " + stringResource(R.string.setup_keyboard_short)
                    } else stringResource(R.string.setup_keyboard_short),
                    badge = if (largeText) null else ({
                        FluidStatusBadge(
                            label = stringResource(if (state.keyboardEnabled) R.string.setup_status_active else R.string.setup_status_to_enable),
                            tone = if (state.keyboardEnabled) FluidTone.Success else FluidTone.Neutral,
                        )
                    }),
                    onClick = onOpenKeyboards,
                )
            }
        }
        item(key = "connection-header") { FluidSectionHeader(stringResource(R.string.settings_section_connection)) }
        item(key = "connection") {
            FluidCard(glass = true) {
                Text(
                    text = stringResource(if (state.hasApiKey) R.string.setup_api_key_ready else R.string.setup_api_key_missing),
                    style = MaterialTheme.typography.titleMedium,
                )
                // La chiave Groq e' un segreto: puntini, occhio per mostrarla, tastiera senza correzione.
                FluidTextField(
                    value = state.apiKeyInput,
                    onValueChange = { state.apiKeyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.settings_api_key),
                    placeholder = stringResource(R.string.settings_api_key_hint),
                    supportingText = if (state.hasApiKey) stringResource(R.string.settings_api_key_saved_hint) else null,
                    secret = true,
                )
                if (largeText) {
                    // A testo grande i due tasti stanno uno sopra l'altro a tutta larghezza: la pagina
                    // scorre da qui solo se i tasti cedono il dito (a 200% sono lunghi tratti di pagina).
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidButton(
                            text = stringResource(R.string.settings_api_key_test),
                            onClick = onTestApiKey,
                            enabled = !state.testingApiKey,
                            loading = state.testingApiKey,
                            fillWidth = true,
                            grip = FluidPressGrip.YieldsToScroll,
                        )
                        FluidButton(
                            text = stringResource(R.string.setup_open_groq),
                            onClick = onOpenGroq,
                            style = FluidButtonStyle.Tinted,
                            fillWidth = true,
                            grip = FluidPressGrip.YieldsToScroll,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidButton(
                            text = stringResource(R.string.settings_api_key_test),
                            onClick = onTestApiKey,
                            enabled = !state.testingApiKey,
                            loading = state.testingApiKey,
                            modifier = Modifier.weight(1f),
                        )
                        FluidButton(
                            text = stringResource(R.string.setup_open_groq),
                            onClick = onOpenGroq,
                            style = FluidButtonStyle.Tinted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item(key = "transcription-header") { FluidSectionHeader(stringResource(R.string.settings_section_transcription)) }
        item(key = "transcription") {
            FluidListGroup(glass = true) {
                PickerRow(
                    title = stringResource(R.string.settings_language),
                    selectedKey = state.language,
                    options = listOf(
                        PreferencesManager.LANGUAGE_ITALIAN to stringResource(R.string.lang_italian),
                        PreferencesManager.LANGUAGE_ENGLISH to stringResource(R.string.lang_english),
                        PreferencesManager.LANGUAGE_AUTO to stringResource(R.string.lang_auto),
                    ),
                    onSelected = { state.language = it },
                )
                FluidListDivider()
                PickerRow(
                    title = stringResource(R.string.settings_whisper_model),
                    selectedKey = state.whisperModel,
                    options = state.whisperOptions.map { it to it },
                    onSelected = { state.whisperModel = it },
                )
            }
        }
        item(key = "model-status") {
            if (state.modelStatus.isNotBlank()) {
                Text(
                    text = state.modelStatus,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "refinement-header") { FluidSectionHeader(stringResource(R.string.settings_section_refinement)) }
        item(key = "refinement") {
            // Un solo item perche' il gruppo e la scheda del prompt crescono e si ritirano insieme,
            // come faceva la card unica di prima (animateContentSize), a passo di FluidMotion.
            Column(
                modifier = Modifier.animateContentSize(FluidMotion.intSize()),
                verticalArrangement = Arrangement.spacedBy(LocalFluidItemSpacing.current),
            ) {
                FluidListGroup(glass = true) {
                    FluidToggleRow(
                        title = stringResource(R.string.settings_phase2_enabled),
                        subtitle = stringResource(R.string.settings_phase2_description),
                        checked = state.phase2,
                        onCheckedChange = { state.phase2 = it },
                    )
                    if (state.phase2) {
                        FluidListDivider()
                        PickerRow(
                            title = stringResource(R.string.settings_prompt_preset),
                            selectedKey = state.preset.id,
                            options = PromptPreset.entries.map { it.id to it.displayName },
                            onSelected = { state.preset = PromptPreset.fromId(it) },
                        )
                        FluidListDivider()
                        PickerRow(
                            title = stringResource(R.string.settings_llm_model),
                            selectedKey = state.llmModel,
                            options = state.llmOptions.map { it to it },
                            onSelected = { state.llmModel = it },
                        )
                    }
                }
                if (state.phase2 && state.preset == PromptPreset.CUSTOM) {
                    FluidCard(glass = true) {
                        FluidTextField(
                            value = state.systemPrompt,
                            onValueChange = { state.systemPrompt = it },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            label = stringResource(R.string.settings_prompt_title),
                            placeholder = stringResource(R.string.settings_prompt_hint),
                            singleLine = false,
                            minLines = 5,
                        )
                        FluidButton(
                            text = stringResource(R.string.settings_reset_prompt),
                            onClick = {
                                state.systemPrompt = PreferencesManager.DEFAULT_SYSTEM_PROMPT
                                state.preset = PromptPreset.CLEAN
                            },
                            style = FluidButtonStyle.Plain,
                        )
                    }
                }
            }
        }
        item(key = "vocab-header") { FluidSectionHeader(stringResource(R.string.settings_vocab_title), detail = stringResource(R.string.settings_vocab_desc)) }
        item(key = "vocab-manual") {
            FluidCard(glass = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidTextField(
                        value = state.vocabularyInput,
                        onValueChange = { state.vocabularyInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.settings_vocab_hint),
                    )
                    FluidButton(text = stringResource(R.string.settings_vocab_add), onClick = onAddVocabulary)
                }
                Text(stringResource(R.string.settings_vocab_manual), style = MaterialTheme.typography.titleSmall)
                if (state.manualTerms.isEmpty()) {
                    SecondaryText(stringResource(R.string.settings_vocab_manual_empty))
                } else {
                    TermChips(
                        terms = state.manualTerms,
                        onRemove = { state.manualTerms.remove(it) },
                        onShowAll = { vocabularySheet = VocabularySheet.Manual },
                    )
                }
            }
        }
        item(key = "vocab-learning") {
            FluidListGroup(glass = true) {
                FluidToggleRow(
                    title = stringResource(R.string.settings_auto_learning),
                    subtitle = stringResource(R.string.settings_auto_learning_description),
                    checked = state.autoLearning,
                    onCheckedChange = { state.autoLearning = it },
                )
            }
        }
        item(key = "vocab-learned") {
            FluidCard(glass = true) {
                Text(stringResource(R.string.settings_vocab_learned), style = MaterialTheme.typography.titleSmall)
                if (state.learnedTerms.isEmpty()) {
                    SecondaryText(stringResource(R.string.settings_vocab_learned_empty))
                } else {
                    TermChips(
                        terms = state.learnedTerms,
                        onRemove = onRemoveLearned,
                        onShowAll = { vocabularySheet = VocabularySheet.Learned },
                    )
                    FluidButton(text = stringResource(R.string.settings_vocab_clear_learned), onClick = onClearLearned, style = FluidButtonStyle.Destructive)
                }
            }
        }
        item(key = "behavior-header") { FluidSectionHeader(stringResource(R.string.settings_section_behavior)) }
        item(key = "behavior-duration") {
            // Il cursore con il suo titolo e il valore: l'engine ha il cursore ma non (ancora) la riga
            // che lo accompagna, quindi resta una scheda scritta qui.
            val valueLabel = stringResource(R.string.settings_max_duration_value, state.duration)
            FluidCard(glass = true) {
                Text(stringResource(R.string.settings_max_duration), style = MaterialTheme.typography.titleMedium)
                SecondaryText(valueLabel)
                FluidSlider(
                    value = state.duration.toFloat(),
                    onValueChange = { state.duration = it.toInt().coerceIn(30, 600) },
                    valueRange = 30f..600f,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = stringResource(R.string.settings_max_duration),
                    stateDescription = valueLabel,
                )
            }
        }
        item(key = "behavior") {
            FluidListGroup(glass = true) {
                FluidToggleRow(
                    title = stringResource(R.string.settings_accessibility_dictation),
                    subtitle = stringResource(R.string.settings_accessibility_dictation_description_primary),
                    checked = state.accessibilityDictation,
                    onCheckedChange = { state.accessibilityDictation = it },
                )
                FluidListDivider()
                FluidListRow(
                    title = stringResource(R.string.settings_accessibility_reset_position),
                    subtitle = "",
                    onClick = onResetBubble,
                )
                FluidListDivider()
                FluidToggleRow(
                    title = stringResource(R.string.settings_haptic),
                    subtitle = stringResource(R.string.settings_haptic_description),
                    checked = state.haptic,
                    onCheckedChange = { state.haptic = it },
                )
            }
        }
        item(key = "keyboard-header") { FluidSectionHeader(stringResource(R.string.settings_section_keyboard_fallback), detail = stringResource(R.string.settings_keyboard_fallback_description)) }
        item(key = "keyboard") {
            FluidListGroup(glass = true) {
                FluidToggleRow(
                    title = stringResource(R.string.settings_auto_start_recording),
                    subtitle = stringResource(R.string.settings_auto_start_recording_description_fallback),
                    checked = state.autoStartRecording,
                    onCheckedChange = { state.autoStartRecording = it },
                )
                FluidListDivider()
                FluidToggleRow(
                    title = stringResource(R.string.settings_preview_long_text),
                    subtitle = stringResource(R.string.settings_preview_long_text_description),
                    checked = state.previewLongText,
                    onCheckedChange = { state.previewLongText = it },
                )
                FluidListDivider()
                FluidToggleRow(
                    title = stringResource(R.string.settings_return_to_previous_keyboard),
                    subtitle = stringResource(R.string.settings_return_to_previous_keyboard_description),
                    checked = state.returnToPreviousKeyboard,
                    onCheckedChange = { state.returnToPreviousKeyboard = it },
                )
            }
        }
        // Gli aggiornamenti li scrive l'engine: versione, "controllato N minuti fa", il tasto che da
        // "Controlla ora" diventa "Installa la X", la barra dentro il gruppo e "Salta questa versione".
        // Quando e' KeyVoice a decidere (ogni quante ore controllare, la notifica) sta nel coordinatore.
        fluidUpdateSection(
            state = state.updateState,
            onCheck = onCheckUpdate,
            onInstall = onInstallUpdate,
            currentVersion = BuildConfig.VERSION_NAME,
            lastCheckedAtMillis = state.lastUpdateCheckMillis,
            onSkip = onIgnoreUpdate,
            glass = true,
        )
        item(key = "save") {
            FluidButton(
                text = stringResource(R.string.settings_save),
                onClick = onSave,
                fillWidth = true,
                // A tutta larghezza non c'e' un "fuori" da cui far partire lo scorrimento.
                grip = FluidPressGrip.YieldsToScroll,
                leading = { Icon(Icons.Rounded.Check, contentDescription = null) },
            )
        }
        // Le opere di altri che l'APK porta con se' e la licenza dell'app (GPL-3.0 dalla 3.0 dell'engine).
        fluidLicensesSection()
    }
    // L'avviso a foglio si apre solo se si arriva dalla notifica: stato nullo, foglio chiuso.
    FluidUpdatePromptSheet(
        state = if (state.updatePromptRequested) state.updateState else null,
        onInstall = onInstallUpdate,
        onLater = onLaterUpdate,
        onSkip = onIgnoreUpdate,
    )
    FluidGlassModalPortal(
        item = vocabularySheet,
        onDismissRequest = { vocabularySheet = null },
        presentation = FluidGlassModalPresentation.Sheet,
        paneTitle = stringResource(
            if (vocabularySheet == VocabularySheet.Learned) R.string.settings_vocab_learned
            else R.string.settings_vocab_manual
        ),
    ) { sheet ->
        val terms = if (sheet == VocabularySheet.Manual) state.manualTerms else state.learnedTerms
        FluidSheetScaffold(
            title = stringResource(
                if (sheet == VocabularySheet.Manual) R.string.settings_vocab_manual
                else R.string.settings_vocab_learned
            ),
            // La lista scorre da sola: un LazyColumn dentro un corpo che scorre non ha un'altezza.
            scrollable = false,
        ) {
            // Le righe stanno in un gruppo, come le alternative dei selettori: i bordi si allineano
            // alla testata del foglio e il vetro e' uno solo.
            FluidListGroup {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    itemsIndexed(terms, key = { _, term -> term }) { index, term ->
                        if (index > 0) FluidListDivider()
                        FluidListRow(
                            title = term,
                            subtitle = "",
                            disclosure = false,
                            badge = {
                                // Un "Rimuovi" per riga di una lista che scorre: non c'e' un fuori da cui
                                // partire, quindi il tasto cede il dito alla lista.
                                FluidButton(
                                    text = stringResource(R.string.settings_vocab_remove),
                                    onClick = {
                                        if (sheet == VocabularySheet.Manual) state.manualTerms.remove(term)
                                        else onRemoveLearned(term)
                                    },
                                    style = FluidButtonStyle.Plain,
                                    grip = FluidPressGrip.YieldsToScroll,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

private const val VOCABULARY_PREVIEW_LIMIT = 12

private enum class VocabularySheet { Manual, Learned }

/** Le parole in anteprima come chip che si tolgono con un tocco, e "Mostra tutte" quando sono tante. */
@Composable
private fun TermChips(
    terms: List<String>,
    onRemove: (String) -> Unit,
    onShowAll: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        terms.take(VOCABULARY_PREVIEW_LIMIT).forEach { term ->
            val removeDescription = stringResource(R.string.settings_vocab_remove_term, term)
            FluidChip(
                label = "×  " + term,
                selected = false,
                onClick = { onRemove(term) },
                modifier = Modifier.semantics { contentDescription = removeDescription },
            )
        }
    }
    if (terms.size > VOCABULARY_PREVIEW_LIMIT) {
        FluidButton(
            text = stringResource(R.string.settings_vocab_show_all, terms.size),
            onClick = onShowAll,
            style = FluidButtonStyle.Plain,
        )
    }
}

/** Cio' che il selettore sta mostrando: preso all'apertura, cosi' il foglio esce intero. */
private data class PickerPanel(
    val title: String,
    val options: List<Pair<String, String>>,
    val selectedKey: String,
)

/**
 * Una riga che mostra il valore scelto e, al tocco, diventa il pannello con le alternative
 * (`FluidChoiceRow` con la spunta a destra, e un corpo che scorre per i cataloghi lunghi).
 *
 * L'engine non ha ancora una riga-selettore: questa e' la forma che le regole chiedono — il portale
 * a `item`, con l'origine della riga —, non il portale a booleano che c'era prima.
 */
@Composable
private fun PickerRow(
    title: String,
    selectedKey: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var panel by remember { mutableStateOf<PickerPanel?>(null) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey
    FluidListRow(
        title = title,
        subtitle = selectedLabel,
        modifier = Modifier.fluidExpandOrigin { origin = it },
        onClick = { panel = PickerPanel(title, options, selectedKey) },
    )
    FluidGlassModalPortal(
        item = panel,
        onDismissRequest = { panel = null },
        origin = { origin },
        paneTitle = title,
    ) { shown ->
        FluidSheetScaffold(title = shown.title, insetBottom = false) {
            FluidListGroup(modifier = Modifier.selectableGroup()) {
                shown.options.forEachIndexed { index, (key, label) ->
                    if (index > 0) FluidListDivider()
                    FluidChoiceRow(
                        title = label,
                        selected = key == shown.selectedKey,
                        onSelect = {
                            onSelected(key)
                            panel = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
