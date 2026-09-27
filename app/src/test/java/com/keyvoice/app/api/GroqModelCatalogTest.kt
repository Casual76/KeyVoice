package com.keyvoice.app.api

import com.keyvoice.app.settings.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqModelCatalogTest {

    @Test
    fun catalogKeepsActiveChatAndTranscriptionModelsInSeparateMenus() {
        val catalog = GroqModelCatalog.from(
            listOf(
                GroqModel(id = "whisper-large-v3", active = true),
                GroqModel(id = "whisper-large-v3-turbo", active = true),
                GroqModel(id = PreferencesManager.MODEL_GPT_OSS_20B, active = true),
                GroqModel(id = PreferencesManager.MODEL_GPT_OSS_120B, active = true),
                GroqModel(id = "openai/gpt-oss-safeguard-20b", active = true),
                GroqModel(id = "meta-llama/llama-prompt-guard-2-86m", active = true),
                GroqModel(id = "canopylabs/orpheus-v1-english", active = true),
                GroqModel(id = "retired-model", active = false)
            )
        )

        assertEquals(
            listOf("whisper-large-v3", "whisper-large-v3-turbo"),
            catalog.transcriptionModels
        )
        assertEquals(
            listOf(
                PreferencesManager.MODEL_GPT_OSS_20B,
                PreferencesManager.MODEL_GPT_OSS_120B
            ),
            catalog.llmModels
        )
    }

    @Test
    fun fallbackUsesCurrentFullyQualifiedGroqModelIds() {
        assertEquals(
            PreferencesManager.MODEL_GPT_OSS_20B,
            GroqModelCatalog.FALLBACK.llmModels.first()
        )
        assertTrue(GroqModelCatalog.FALLBACK.llmModels.all { "/" in it })
        assertFalse(GroqModelCatalog.FALLBACK.llmModels.contains("gpt-oss-20b"))
    }

    @Test
    fun retiredSavedModelsMigrateOnlyWithTheSavedKeysCatalog() {
        val catalog = GroqModelCatalog(
            transcriptionModels = listOf("whisper-large-v3-turbo"),
            llmModels = listOf("openai/gpt-oss-120b"),
        )

        val selection = catalog.resolveSelection(
            draftWhisper = "whisper-large-v3",
            draftLlm = "openai/gpt-oss-20b",
            savedWhisper = "whisper-large-v3",
            savedLlm = "openai/gpt-oss-20b",
            catalogBelongsToSavedKey = true,
        )

        assertEquals("whisper-large-v3-turbo", selection.draftWhisper)
        assertEquals("openai/gpt-oss-120b", selection.draftLlm)
        assertEquals("whisper-large-v3-turbo", selection.savedWhisperReplacement)
        assertEquals("openai/gpt-oss-120b", selection.savedLlmReplacement)
    }

    @Test
    fun proposedKeysCatalogChangesDraftWithoutMigratingSavedModels() {
        val catalog = GroqModelCatalog(
            transcriptionModels = listOf("whisper-large-v3-turbo"),
            llmModels = listOf("openai/gpt-oss-120b"),
        )

        val selection = catalog.resolveSelection(
            draftWhisper = "whisper-large-v3",
            draftLlm = "openai/gpt-oss-20b",
            savedWhisper = "whisper-large-v3",
            savedLlm = "openai/gpt-oss-20b",
            catalogBelongsToSavedKey = false,
        )

        assertEquals("whisper-large-v3-turbo", selection.draftWhisper)
        assertEquals("openai/gpt-oss-120b", selection.draftLlm)
        assertNull(selection.savedWhisperReplacement)
        assertNull(selection.savedLlmReplacement)
    }

    @Test
    fun currentModelsRemainSelectedAndDoNotTriggerPreferenceWrites() {
        val catalog = GroqModelCatalog.FALLBACK

        val selection = catalog.resolveSelection(
            draftWhisper = PreferencesManager.DEFAULT_WHISPER_MODEL,
            draftLlm = PreferencesManager.DEFAULT_LLM_MODEL,
            savedWhisper = PreferencesManager.DEFAULT_WHISPER_MODEL,
            savedLlm = PreferencesManager.DEFAULT_LLM_MODEL,
            catalogBelongsToSavedKey = true,
        )

        assertEquals(PreferencesManager.DEFAULT_WHISPER_MODEL, selection.draftWhisper)
        assertEquals(PreferencesManager.DEFAULT_LLM_MODEL, selection.draftLlm)
        assertNull(selection.savedWhisperReplacement)
        assertNull(selection.savedLlmReplacement)
    }
}
