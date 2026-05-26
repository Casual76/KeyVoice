package com.keyvoice.app.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionFormatterTest {

    @Test
    fun addsLeadingSpaceBetweenWords() {
        val text = InsertionFormatter.format(
            rawText = "ciao",
            context = InsertionFormatter.Context(beforeCursor = "hello")
        )

        assertEquals(" ciao", text)
    }

    @Test
    fun doesNotAddSpaceBeforePunctuation() {
        val text = InsertionFormatter.format(
            rawText = ".",
            context = InsertionFormatter.Context(beforeCursor = "hello")
        )

        assertEquals(".", text)
    }

    @Test
    fun addsTrailingSpaceWhenInsertedBetweenWords() {
        val text = InsertionFormatter.format(
            rawText = "beautiful",
            context = InsertionFormatter.Context(beforeCursor = "hello ", afterCursor = "world")
        )

        assertEquals("beautiful ", text)
    }

    @Test
    fun capitalizesAtStartOfField() {
        val text = InsertionFormatter.format(
            rawText = "ciao mondo",
            context = InsertionFormatter.Context()
        )

        assertEquals("Ciao mondo", text)
    }

    @Test
    fun capitalizesAfterSentencePunctuation() {
        val text = InsertionFormatter.format(
            rawText = "domani arrivo",
            context = InsertionFormatter.Context(beforeCursor = "Ok. ")
        )

        assertEquals("Domani arrivo", text)
    }
}
