package com.keyvoice.app.ime

/**
 * Formats text before committing it into an editor, using the text around the cursor.
 */
object InsertionFormatter {

    data class Context(
        val beforeCursor: String = "",
        val afterCursor: String = "",
        val selectedText: String = ""
    )

    fun format(rawText: String, context: Context): String {
        var text = rawText.trim()
        if (text.isBlank()) return ""

        if (shouldCapitalize(context.beforeCursor)) {
            text = text.capitalizeFirstLetter()
        }

        val prefix = if (needsLeadingSpace(context.beforeCursor, text)) " " else ""
        val suffix = if (needsTrailingSpace(text, context.afterCursor)) " " else ""

        return prefix + text + suffix
    }

    private fun needsLeadingSpace(beforeCursor: String, text: String): Boolean {
        val previous = beforeCursor.lastOrNull() ?: return false
        val first = text.firstOrNull() ?: return false

        if (previous.isWhitespace()) return false
        if (previous in "([{") return false
        if (previous in "\"'") return false
        if (first.isClosingOrPunctuation()) return false
        if (!first.startsWordLikeText()) return false

        return previous.isLetterOrDigit() || previous in ".,;:!?)]}\"'"
    }

    private fun needsTrailingSpace(text: String, afterCursor: String): Boolean {
        val last = text.lastOrNull() ?: return false
        val next = afterCursor.firstOrNull() ?: return false

        if (last.isWhitespace() || next.isWhitespace()) return false
        if (!next.startsWordLikeText()) return false
        if (last in "([{") return false

        return last.isLetterOrDigit() || last in ".,;:!?)]}\"'"
    }

    private fun shouldCapitalize(beforeCursor: String): Boolean {
        val before = beforeCursor.trimEnd()
        if (before.isEmpty()) return true
        if (before.last() == '\n') return true
        return before.last() in ".?!"
    }

    private fun String.capitalizeFirstLetter(): String {
        val index = indexOfFirst { it.isLetter() }
        if (index < 0) return this
        val char = this[index]
        if (char.isUpperCase()) return this
        return replaceRange(index, index + 1, char.uppercase())
    }

    private fun Char.startsWordLikeText(): Boolean {
        return isLetterOrDigit() || this in "\"'("
    }

    private fun Char.isClosingOrPunctuation(): Boolean {
        return this in ".,;:!?)]}"
    }
}
