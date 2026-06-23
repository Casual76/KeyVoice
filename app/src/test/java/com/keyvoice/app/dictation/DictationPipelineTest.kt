package com.keyvoice.app.dictation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DictationPipelineTest {

    @Test
    fun processUsesPhase2WhenRefinementSucceeds() = runBlocking {
        val pipeline = DictationPipeline(
            transcribeAudio = { _, _, _, _, _ -> Result.success("ciao virgola come va") },
            refineText = { _, _, _, _, _ -> Result.success("Ciao, come va.") }
        )

        val output = pipeline.process(File("ignored.m4a"), request()).getOrThrow()

        assertEquals("ciao virgola come va", output.rawText)
        assertEquals("Ciao, come va", output.finalText)
        assertEquals(true, output.phase2Used)
        assertNull(output.fallbackReason)
    }

    @Test
    fun processKeepsQuestionMarkAtEnd() = runBlocking {
        val pipeline = DictationPipeline(
            transcribeAudio = { _, _, _, _, _ -> Result.success("come va") },
            refineText = { _, _, _, _, _ -> Result.success("Come va?") }
        )

        val output = pipeline.process(File("ignored.m4a"), request()).getOrThrow()

        assertEquals("Come va?", output.finalText)
    }

    @Test
    fun processFallsBackToSpokenPunctuationWhenRefinementFails() = runBlocking {
        val pipeline = DictationPipeline(
            transcribeAudio = { _, _, _, _, _ -> Result.success("ciao virgola come va") },
            refineText = { _, _, _, _, _ -> Result.failure(IllegalStateException("offline")) }
        )

        val output = pipeline.process(File("ignored.m4a"), request()).getOrThrow()

        assertEquals("ciao, come va", output.finalText)
        assertEquals(false, output.phase2Used)
        assertEquals(DictationPipeline.FallbackReason.PHASE2_UNAVAILABLE, output.fallbackReason)
    }

    @Test
    fun processSkipsRefinementWhenPhase2IsDisabled() = runBlocking {
        var refineCalled = false
        val pipeline = DictationPipeline(
            transcribeAudio = { _, _, _, _, _ -> Result.success("prima nuova riga appunto") },
            refineText = { _, _, _, _, _ ->
                refineCalled = true
                Result.success("should not be used")
            }
        )

        val output = pipeline.process(
            File("ignored.m4a"),
            request(phase2Enabled = false)
        ).getOrThrow()

        assertEquals("prima\nappunto", output.finalText)
        assertEquals(false, output.phase2Used)
        assertEquals(false, refineCalled)
        assertEquals(DictationPipeline.FallbackReason.PHASE2_DISABLED, output.fallbackReason)
    }

    @Test
    fun historyFactoryCreatesStableMetadata() {
        val item = DictationHistoryItems.create(
            rawText = "raw",
            finalText = "final",
            insertedText = " final",
            promptPreset = "clean",
            phase2Used = true,
            nowMillis = 42L
        )

        assertEquals("42", item.id)
        assertEquals(42L, item.createdAtMillis)
        assertEquals("raw", item.rawText)
        assertEquals("final", item.finalText)
        assertEquals(" final", item.insertedText)
        assertEquals("clean", item.promptPreset)
        assertEquals(true, item.phase2Used)
    }

    private fun request(phase2Enabled: Boolean = true): DictationPipeline.Request {
        return DictationPipeline.Request(
            apiKey = "key",
            whisperModel = "whisper-large-v3",
            language = "it",
            languageFullName = "italiano",
            llmModel = "gpt-oss-20b",
            phase2Enabled = phase2Enabled,
            systemPrompt = "Clean {LINGUA_CONFIGURATA}",
            vocabulary = "KeyVoice",
            promptPreset = "clean"
        )
    }
}
