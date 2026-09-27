package com.keyvoice.app.api

import com.keyvoice.app.settings.PreferencesManager

data class GroqModelCatalog(
    val transcriptionModels: List<String>,
    val llmModels: List<String>
) {
    internal fun resolveSelection(
        draftWhisper: String,
        draftLlm: String,
        savedWhisper: String,
        savedLlm: String,
        catalogBelongsToSavedKey: Boolean,
    ): GroqModelSelection = GroqModelSelection(
        draftWhisper = draftWhisper.takeIf { it in transcriptionModels }
            ?: transcriptionModels.firstOrNull() ?: draftWhisper,
        draftLlm = draftLlm.takeIf { it in llmModels }
            ?: llmModels.firstOrNull() ?: draftLlm,
        savedWhisperReplacement = if (catalogBelongsToSavedKey && savedWhisper !in transcriptionModels) {
            transcriptionModels.firstOrNull()
        } else null,
        savedLlmReplacement = if (catalogBelongsToSavedKey && savedLlm !in llmModels) {
            llmModels.firstOrNull()
        } else null,
    )

    companion object {
        val FALLBACK = GroqModelCatalog(
            transcriptionModels = listOf(
                PreferencesManager.DEFAULT_WHISPER_MODEL,
                "whisper-large-v3-turbo"
            ),
            llmModels = listOf(
                PreferencesManager.MODEL_GPT_OSS_20B,
                PreferencesManager.MODEL_GPT_OSS_120B,
                PreferencesManager.MODEL_QWEN_3_6_27B
            )
        )

        fun from(models: List<GroqModel>): GroqModelCatalog {
            val activeIds = models
                .asSequence()
                .filter { it.active != false }
                .mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .toList()

            val transcriptionModels = activeIds
                .filter(::isTranscriptionModel)
                .sortedWith(preferredFirst(PreferencesManager.DEFAULT_WHISPER_MODEL))

            val llmModels = activeIds
                .filterNot(::isTranscriptionModel)
                .filterNot(::isNonGenerativeModel)
                .sortedWith(preferredFirst(PreferencesManager.DEFAULT_LLM_MODEL))

            return GroqModelCatalog(
                transcriptionModels = transcriptionModels,
                llmModels = llmModels
            )
        }

        internal fun isTranscriptionModel(modelId: String): Boolean {
            return modelId.lowercase().contains("whisper")
        }

        internal fun isNonGenerativeModel(modelId: String): Boolean {
            val id = modelId.lowercase()
            return listOf(
                "prompt-guard",
                "llama-guard",
                "safeguard",
                "orpheus",
                "playai-tts",
                "text-to-speech",
                "/tts"
            ).any(id::contains)
        }

        private fun preferredFirst(preferredModel: String): Comparator<String> {
            return compareBy<String> { it != preferredModel }.thenBy { it.lowercase() }
        }
    }
}

internal data class GroqModelSelection(
    val draftWhisper: String,
    val draftLlm: String,
    val savedWhisperReplacement: String?,
    val savedLlmReplacement: String?,
)
