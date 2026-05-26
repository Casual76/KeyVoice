package com.keyvoice.app.ime

/**
 * Extracts likely custom vocabulary terms from a user edit to text inserted by KeyVoice.
 */
object TextCorrectionLearner {

    private const val MAX_CHANGED_WORDS = 8
    private const val MAX_TERM_LENGTH = 48

    private val wordRegex = Regex("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}'_-]*")

    fun extractNewTerms(originalText: String, editedText: String): List<String> {
        if (originalText.isBlank() || editedText.isBlank() || originalText == editedText) {
            return emptyList()
        }

        val originalWords = extractWords(originalText)
        val editedWords = extractWords(editedText)
        if (originalWords.isEmpty() || editedWords.isEmpty()) return emptyList()

        var prefix = 0
        while (
            prefix < originalWords.size &&
            prefix < editedWords.size &&
            originalWords[prefix].normalized == editedWords[prefix].normalized
        ) {
            prefix++
        }

        var suffix = 0
        while (
            suffix + prefix < originalWords.size &&
            suffix + prefix < editedWords.size &&
            originalWords[originalWords.lastIndex - suffix].normalized ==
            editedWords[editedWords.lastIndex - suffix].normalized
        ) {
            suffix++
        }

        val changedOriginal = originalWords.subList(prefix, originalWords.size - suffix)
        val changedEdited = editedWords.subList(prefix, editedWords.size - suffix)

        if (changedEdited.isEmpty()) return emptyList()
        if (changedOriginal.size > MAX_CHANGED_WORDS || changedEdited.size > MAX_CHANGED_WORDS) {
            return emptyList()
        }

        val originalVocabulary = originalWords.map { it.normalized }.toSet()
        val additions = linkedSetOf<String>()

        changedEdited.forEach { word ->
            if (word.normalized !in originalVocabulary && isLearnableTerm(word.value)) {
                additions += word.value
            }
        }

        return additions.toList()
    }

    private fun extractWords(text: String): List<WordToken> {
        return wordRegex.findAll(text)
            .map { match -> match.value.trimWordEdges() }
            .filter { it.isNotBlank() }
            .map { WordToken(it, it.normalizedKey()) }
            .filter { it.normalized.isNotBlank() }
            .toList()
    }

    private fun isLearnableTerm(term: String): Boolean {
        val lettersAndDigits = term.count { it.isLetterOrDigit() }
        if (lettersAndDigits < 3 || term.length > MAX_TERM_LENGTH) return false
        if (term.all { it.isDigit() }) return false

        val hasSignal = term.any { it.isUpperCase() } ||
            term.any { it.isDigit() } ||
            lettersAndDigits >= 5

        return hasSignal
    }

    private fun String.trimWordEdges(): String {
        return trim { char -> !char.isLetterOrDigit() }
    }

    private fun String.normalizedKey(): String {
        return lowercase().trimWordEdges()
    }

    private data class WordToken(
        val value: String,
        val normalized: String
    )
}
