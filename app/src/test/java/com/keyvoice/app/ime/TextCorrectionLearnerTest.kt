package com.keyvoice.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCorrectionLearnerTest {

    @Test
    fun learnsNewCorrectedProperTerm() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "uso key voice ogni giorno",
            editedText = "uso KeyVoice ogni giorno"
        )

        assertEquals(listOf("KeyVoice"), terms)
    }

    @Test
    fun ignoresLargeRewrites() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "questo testo breve era sbagliato",
            editedText = "KeyVoice Groq Whisper Kotlin Android Retrofit Coroutines Material Gradle"
        )

        assertTrue(terms.isEmpty())
    }

    @Test
    fun ignoresPureDeletion() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "usa Key Voice oggi",
            editedText = "usa oggi"
        )

        assertTrue(terms.isEmpty())
    }
}
