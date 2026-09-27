package com.keyvoice.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.keyvoice.app.BuildConfig
import com.keyvoice.app.R
import com.keyvoice.app.SetupState
import com.keyvoice.app.isBusy
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.settings.PromptPreset
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidHeroCard
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
        item(key = "hero") {
            FluidHeroCard(
                title = stringResource(if (ready) R.string.setup_wizard_title_ready else R.string.setup_wizard_title),
                subtitle = stringResource(
                    when {
                        !state.hasApiKey -> R.string.setup_wizard_status_api_missing
                        !state.accessibilityEnabled -> R.string.setup_wizard_status_accessibility_missing
                        else -> R.string.setup_wizard_status_ready
                    }
                ),
                trailing = { Icon(Icons.Rounded.Mic, contentDescription = null) },
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
                FluidTextField(
                    value = state.apiKeyInput,
                    onValueChange = { state.apiKeyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.settings_api_key),
                    placeholder = stringResource(R.string.settings_api_key_hint),
                    supportingText = if (state.hasApiKey) stringResource(R.string.settings_api_key_saved_hint) else null,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidButton(
                            text = stringResource(R.string.settings_api_key_test),
                            onClick = onTestApiKey,
                            enabled = !state.testingApiKey,
                            loading = state.testingApiKey,
                            fillWidth = true,
                        )
                        FluidButton(
                            text = stringResource(R.string.setup_open_groq),
                            onClick = onOpenGroq,
                            style = FluidButtonStyle.Tinted,
                            fillWidth = true,
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
                ChoiceRow(
                    title = stringResource(R.string.settings_language),
                    selected = when (state.language) {
                        PreferencesManager.LANGUAGE_ENGLISH -> stringResource(R.string.lang_english)
                        PreferencesManager.LANGUAGE_AUTO -> stringResource(R.string.lang_auto)
                        else -> stringResource(R.string.lang_italian)
                    },
                    options = listOf(
                        PreferencesManager.LANGUAGE_ITALIAN to stringResource(R.string.lang_italian),
                        PreferencesManager.LANGUAGE_ENGLISH to stringResource(R.string.lang_english),
                        PreferencesManager.LANGUAGE_AUTO to stringResource(R.string.lang_auto),
                    ),
                    onSelected = { state.language = it },
                )
                FluidListDivider()
                ChoiceRow(
                    title = stringResource(R.string.settings_whisper_model),
                    selected = state.whisperModel,
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
            FluidCard(glass = true) {
                ToggleRow(
                    title = stringResource(R.string.settings_phase2_enabled),
                    subtitle = stringResource(R.string.settings_phase2_description),
                    checked = state.phase2,
                    onChecked = { state.phase2 = it },
                )
                if (state.phase2) {
                    ChoiceRow(
                        title = stringResource(R.string.settings_prompt_preset),
                        selected = state.preset.displayName,
                        options = PromptPreset.entries.map { it.id to it.displayName },
                        onSelected = { state.preset = PromptPreset.fromId(it) },
                    )
                    ChoiceRow(
                        title = stringResource(R.string.settings_llm_model),
                        selected = state.llmModel,
                        options = state.llmOptions.map { it to it },
                        onSelected = { state.llmModel = it },
                    )
                    if (state.preset == PromptPreset.CUSTOM) {
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
        item(key = "vocab") {
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.manualTerms.take(VOCABULARY_PREVIEW_LIMIT).forEach { term ->
                            val removeDescription = stringResource(R.string.settings_vocab_remove_term, term)
                            FluidChip(
                                label = "×  " + term,
                                selected = false,
                                onClick = { state.manualTerms.remove(term) },
                                modifier = Modifier.semantics { contentDescription = removeDescription },
                            )
                        }
                    }
                    if (state.manualTerms.size > VOCABULARY_PREVIEW_LIMIT) {
                        FluidButton(
                            text = stringResource(R.string.settings_vocab_show_all, state.manualTerms.size),
                            onClick = { vocabularySheet = VocabularySheet.Manual },
                            style = FluidButtonStyle.Plain,
                        )
                    }
                }
                ToggleRow(
                    title = stringResource(R.string.settings_auto_learning),
                    subtitle = stringResource(R.string.settings_auto_learning_description),
                    checked = state.autoLearning,
                    onChecked = { state.autoLearning = it },
                )
                Text(stringResource(R.string.settings_vocab_learned), style = MaterialTheme.typography.titleSmall)
                if (state.learnedTerms.isEmpty()) {
                    SecondaryText(stringResource(R.string.settings_vocab_learned_empty))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.learnedTerms.take(VOCABULARY_PREVIEW_LIMIT).forEach { term ->
                            val removeDescription = stringResource(R.string.settings_vocab_remove_term, term)
                            FluidChip(
                                label = "×  " + term,
                                selected = false,
                                onClick = { onRemoveLearned(term) },
                                modifier = Modifier.semantics { contentDescription = removeDescription },
                            )
                        }
                    }
                    if (state.learnedTerms.size > VOCABULARY_PREVIEW_LIMIT) {
                        FluidButton(
                            text = stringResource(R.string.settings_vocab_show_all, state.learnedTerms.size),
                            onClick = { vocabularySheet = VocabularySheet.Learned },
                            style = FluidButtonStyle.Plain,
                        )
                    }
                    FluidButton(text = stringResource(R.string.settings_vocab_clear_learned), onClick = onClearLearned, style = FluidButtonStyle.Destructive)
                }
            }
        }
        item(key = "behavior-header") { FluidSectionHeader(stringResource(R.string.settings_section_behavior)) }
        item(key = "behavior") {
            FluidCard(glass = true) {
                Text(stringResource(R.string.settings_max_duration), style = MaterialTheme.typography.titleMedium)
                SecondaryText(stringResource(R.string.settings_max_duration_value, state.duration))
                FluidSlider(
                    value = state.duration.toFloat(),
                    onValueChange = { state.duration = it.toInt().coerceIn(30, 600) },
                    valueRange = 30f..600f,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = stringResource(R.string.settings_accessibility_dictation),
                    subtitle = stringResource(R.string.settings_accessibility_dictation_description_primary),
                    checked = state.accessibilityDictation,
                    onChecked = { state.accessibilityDictation = it },
                )
                FluidButton(text = stringResource(R.string.settings_accessibility_reset_position), onClick = onResetBubble, style = FluidButtonStyle.Plain)
                ToggleRow(
                    title = stringResource(R.string.settings_haptic),
                    subtitle = stringResource(R.string.settings_haptic_description),
                    checked = state.haptic,
                    onChecked = { state.haptic = it },
                )
            }
        }
        item(key = "keyboard-header") { FluidSectionHeader(stringResource(R.string.settings_section_keyboard_fallback), detail = stringResource(R.string.settings_keyboard_fallback_description)) }
        item(key = "keyboard") {
            FluidCard(glass = true) {
                ToggleRow(
                    title = stringResource(R.string.settings_auto_start_recording),
                    subtitle = stringResource(R.string.settings_auto_start_recording_description_fallback),
                    checked = state.autoStartRecording,
                    onChecked = { state.autoStartRecording = it },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_preview_long_text),
                    subtitle = stringResource(R.string.settings_preview_long_text_description),
                    checked = state.previewLongText,
                    onChecked = { state.previewLongText = it },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_return_to_previous_keyboard),
                    subtitle = stringResource(R.string.settings_return_to_previous_keyboard_description),
                    checked = state.returnToPreviousKeyboard,
                    onChecked = { state.returnToPreviousKeyboard = it },
                )
            }
        }
        item(key = "updates-header") { FluidSectionHeader(stringResource(R.string.settings_section_updates)) }
        item(key = "updates") {
            FluidCard(glass = true) {
                Text(stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                SecondaryText(updateStatus(state))
                state.availableUpdate?.let { update ->
                    if (update.changelog.isNotBlank()) SecondaryText(update.changelog)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidButton(
                            text = stringResource(R.string.update_action_install),
                            onClick = onInstallUpdate,
                            enabled = !state.installState.isBusy() && state.installState !is AppUpdateInstallState.Installed,
                        )
                        FluidButton(text = stringResource(R.string.update_action_later), onClick = onLaterUpdate, style = FluidButtonStyle.Tinted)
                    }
                    FluidButton(text = stringResource(R.string.update_action_ignore), onClick = onIgnoreUpdate, style = FluidButtonStyle.Plain)
                }
                FluidButton(
                    text = stringResource(R.string.update_action_check),
                    onClick = onCheckUpdate,
                    enabled = !state.checkingUpdate && !state.installState.isBusy(),
                    loading = state.checkingUpdate,
                    style = FluidButtonStyle.Tinted,
                )
            }
        }
        item(key = "save") {
            FluidButton(
                text = stringResource(R.string.settings_save),
                onClick = onSave,
                fillWidth = true,
                leading = { Icon(Icons.Rounded.Check, contentDescription = null) },
            )
        }
    }
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
        Text(
            text = stringResource(
                if (sheet == VocabularySheet.Manual) R.string.settings_vocab_manual
                else R.string.settings_vocab_learned
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(terms, key = { it }) { term ->
                FluidListRow(
                    title = term,
                    subtitle = "",
                    disclosure = false,
                    badge = {
                        FluidButton(
                            text = stringResource(R.string.settings_vocab_remove),
                            onClick = {
                                if (sheet == VocabularySheet.Manual) state.manualTerms.remove(term)
                                else onRemoveLearned(term)
                            },
                            style = FluidButtonStyle.Plain,
                        )
                    },
                )
                FluidListDivider()
            }
        }
    }
    }
}

private const val VOCABULARY_PREVIEW_LIMIT = 12

private enum class VocabularySheet { Manual, Learned }

@Composable
private fun ChoiceRow(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FluidListRow(title = title, subtitle = selected, onClick = { expanded = true })
        FluidGlassModalPortal(
            visible = expanded,
            onDismissRequest = { expanded = false },
            presentation = FluidGlassModalPresentation.Sheet,
            paneTitle = title,
        ) {
            FluidListGroup {
                options.forEachIndexed { index, (key, label) ->
                    if (index > 0) FluidListDivider()
                    FluidListRow(
                        title = label,
                        subtitle = "",
                        disclosure = false,
                        badge = if (label == selected) {
                            { FluidStatusBadge(stringResource(R.string.settings_choice_selected), tone = FluidTone.Success) }
                        } else null,
                        onClick = {
                            onSelected(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SecondaryText(subtitle)
        }
        FluidSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun updateStatus(state: SetupState): String {
    return when (val install = state.installState) {
        is AppUpdateInstallState.Downloading -> stringResource(R.string.update_install_downloading, (install.progress * 100).toInt().coerceIn(0, 100))
        is AppUpdateInstallState.Verifying -> install.message
        is AppUpdateInstallState.AwaitingUserAction -> install.message
        is AppUpdateInstallState.Installing -> install.message
        is AppUpdateInstallState.Installed -> stringResource(R.string.update_install_installed)
        is AppUpdateInstallState.Error -> install.message
        null -> state.updateMessage.ifBlank { stringResource(R.string.update_status_idle) }
    }
}
