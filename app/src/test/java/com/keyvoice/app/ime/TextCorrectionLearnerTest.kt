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
    fun learnsLowercaseReplacementFromUserCorrection() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "arrivo gisa dopo",
            editedText = "arrivo gia dopo"
        )

        assertEquals(listOf("gia"), terms)
    }

    @Test
    fun doesNotLearnShortPlainAppend() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "arrivo dopo",
            editedText = "arrivo dopo gia"
        )

        assertTrue(terms.isEmpty())
    }

    @Test
    fun doesNotLearnLongPlainAppend() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "arrivo dopo",
            editedText = "arrivo dopo domani"
        )

        assertTrue(terms.isEmpty())
    }

    @Test
    fun learnsExplicitTermAppend() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "uso spesso",
            editedText = "uso spesso KeyVoice"
        )

        assertEquals(listOf("KeyVoice"), terms)
    }

    @Test
    fun ignoresPlainRewriteThatDoesNotLookLikeMishearing() {
        val terms = TextCorrectionLearner.extractNewTerms(
            originalText = "scrivi questo testo",
            editedText = "scrivi questo messaggio"
        )

        assertTrue(terms.isEmpty())
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
