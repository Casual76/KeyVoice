package com.keyvoice.app.dictation

import com.keyvoice.app.api.RefinementRepository
import com.keyvoice.app.api.TranscriptionRepository
import com.keyvoice.app.ime.SpokenPunctuationNormalizer
import com.keyvoice.app.settings.DictationHistoryItem
import java.io.File

class DictationPipeline(
    private val transcribeAudio: suspend (
        audioFile: File,
        apiKey: String,
        model: String,
        language: String?,
        vocabulary: String
    ) -> Result<String> = { audioFile, apiKey, model, language, vocabulary ->
        TranscriptionRepository().transcribe(audioFile, apiKey, model, language, vocabulary)
    },
    private val refineText: suspend (
        rawText: String,
        apiKey: String,
        model: String,
        language: String,
        systemPromptTemplate: String
    ) -> Result<String> = { rawText, apiKey, model, language, systemPromptTemplate ->
        RefinementRepository().refine(rawText, apiKey, model, language, systemPromptTemplate)
    }
) {

    enum class Stage {
        TRANSCRIBING,
        REFINING
    }

    enum class FallbackReason {
        PHASE2_DISABLED,
        PHASE2_UNAVAILABLE
    }

    data class Request(
        val apiKey: String,
        val whisperModel: String,
        val language: String?,
        val languageFullName: String,
        val llmModel: String,
        val phase2Enabled: Boolean,
        val systemPrompt: String,
        val vocabulary: String,
        val promptPreset: String
    )

    data class Output(
        val rawText: String,
        val finalText: String,
        val phase2Used: Boolean,
        val promptPreset: String,
        val fallbackReason: FallbackReason?
    )

    suspend fun process(
        audioFile: File,
        request: Request,
        onStage: suspend (Stage) -> Unit = {}
    ): Result<Output> {
        onStage(Stage.TRANSCRIBING)
        val rawText = transcribeAudio(
            audioFile,
            request.apiKey,
            request.whisperModel,
            request.language,
            request.vocabulary
        ).getOrElse { error ->
            return Result.failure(error)
        }

        if (!request.phase2Enabled) {
            return Result.success(
                Output(
                    rawText = rawText,
                    finalText = DictationTextPostProcessor.cleanForInsertion(
                        SpokenPunctuationNormalizer.normalize(rawText)
                    ),
                    phase2Used = false,
                    promptPreset = request.promptPreset,
                    fallbackReason = FallbackReason.PHASE2_DISABLED
                )
            )
        }

        onStage(Stage.REFINING)
        val refinedText = refineText(
            rawText,
            request.apiKey,
            request.llmModel,
            request.languageFullName,
            request.systemPrompt
        ).getOrElse {
            return Result.success(
                Output(
                    rawText = rawText,
                    finalText = DictationTextPostProcessor.cleanForInsertion(
                        SpokenPunctuationNormalizer.normalize(rawText)
                    ),
                    phase2Used = false,
                    promptPreset = request.promptPreset,
                    fallbackReason = FallbackReason.PHASE2_UNAVAILABLE
                )
            )
        }

        return Result.success(
            Output(
                rawText = rawText,
                finalText = DictationTextPostProcessor.cleanForInsertion(refinedText),
                phase2Used = true,
                promptPreset = request.promptPreset,
                fallbackReason = null
            )
        )
    }
}

object DictationTextPostProcessor {
    fun cleanForInsertion(text: String): String {
        return text.trimEnd().withoutSingleTrailingPeriod()
    }

    private fun String.withoutSingleTrailingPeriod(): String {
        if (!endsWith(".")) return this
        if (endsWith("..")) return this
        return dropLast(1).trimEnd()
    }
}

object DictationHistoryItems {
    fun create(
        rawText: String,
        finalText: String,
        insertedText: String,
        promptPreset: String,
        phase2Used: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): DictationHistoryItem {
        return DictationHistoryItem(
            id = nowMillis.toString(),
            createdAtMillis = nowMillis,
            rawText = rawText,
            finalText = finalText,
            insertedText = insertedText,
            promptPreset = promptPreset,
            phase2Used = phase2Used
        )
    }
}
