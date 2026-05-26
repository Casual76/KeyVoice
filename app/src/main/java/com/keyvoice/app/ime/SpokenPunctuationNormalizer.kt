package com.keyvoice.app.ime

/**
 * Converts conservative spoken punctuation commands when LLM refinement is unavailable.
 */
object SpokenPunctuationNormalizer {

    private val replacements = listOf(
        "nuovo paragrafo" to "\n\n",
        "new paragraph" to "\n\n",
        "punto interrogativo" to "?",
        "question mark" to "?",
        "punto esclamativo" to "!",
        "exclamation mark" to "!",
        "punto e virgola" to ";",
        "semicolon" to ";",
        "nuova riga" to "\n",
        "new line" to "\n",
        "due punti" to ":",
        "colon" to ":",
        "virgola" to ",",
        "comma" to ",",
        "punto" to ".",
        "period" to ".",
        "trattino" to "-",
        "dash" to "-"
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return text

        var normalized = text
        replacements.forEach { (spoken, symbol) ->
            val pattern = Regex(
                "(?i)(?<![\\p{L}\\p{N}])${Regex.escape(spoken)}(?![\\p{L}\\p{N}])"
            )
            normalized = normalized.replace(pattern, symbol)
        }

        return normalized
            .replace(Regex("[ \\t]+([.,;:!?])"), "$1")
            .replace(Regex("([.,;:!?])(?=\\S)"), "$1 ")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }
}
