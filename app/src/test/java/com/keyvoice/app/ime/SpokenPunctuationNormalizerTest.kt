package com.keyvoice.app.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenPunctuationNormalizerTest {

    @Test
    fun normalizesItalianPunctuation() {
        val text = SpokenPunctuationNormalizer.normalize(
            "ciao virgola come stai punto interrogativo"
        )

        assertEquals("ciao, come stai?", text)
    }

    @Test
    fun normalizesEnglishNewLine() {
        val text = SpokenPunctuationNormalizer.normalize(
            "first line new line second line period"
        )

        assertEquals("first line\nsecond line.", text)
    }

    @Test
    fun doesNotReplaceInsideWords() {
        val text = SpokenPunctuationNormalizer.normalize("appunto resta una parola")

        assertEquals("appunto resta una parola", text)
    }
}
