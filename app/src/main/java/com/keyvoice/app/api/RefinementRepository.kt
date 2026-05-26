package com.keyvoice.app.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.keyvoice.app.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Phase 2: Refines raw transcribed text using Groq's LLM Chat Completions API.
 * Corrects punctuation, grammar, and typical speech-to-text errors.
 */
class RefinementRepository {

    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val apiService: GroqApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }

    /**
     * Refines raw transcribed text using an LLM.
     *
     * @param rawText The raw text from Phase 1 (Whisper transcription)
     * @param apiKey The Groq API key
     * @param model The LLM model identifier to use
     * @param language The configured language name for the system prompt
     * @return Result containing the refined text
     */
    suspend fun refine(
        rawText: String,
        apiKey: String,
        model: String,
        language: String,
        systemPromptTemplate: String
    ): Result<String> {
        return try {
            val systemPrompt = systemPromptTemplate.replace("{LINGUA_CONFIGURATA}", language)

            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = rawText)
                ),
                temperature = 0.3,
                max_tokens = 2048
            )

            val response = apiService.chatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            when {
                response.isSuccessful -> {
                    val body = response.body()
                    val refinedText = body?.choices?.firstOrNull()?.message?.content?.trim()

                    if (refinedText.isNullOrBlank()) {
                        // If LLM returns empty, fall back to the raw text
                        Result.success(rawText)
                    } else {
                        Result.success(refinedText)
                    }
                }
                else -> {
                    Result.failure(ApiErrorMapper.fromResponse(response.code(), response.errorBody()))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(ApiException("Timeout: riprova", -1))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(ApiException("Connessione assente", -2))
        } catch (e: java.io.IOException) {
            Result.failure(ApiException("Connessione assente", -2))
        } catch (e: Exception) {
            Result.failure(ApiException("Errore: ${e.localizedMessage}", -3))
        }
    }

    suspend fun rewrite(
        text: String,
        apiKey: String,
        model: String,
        language: String,
        instruction: String
    ): Result<String> {
        return try {
            val systemPrompt = """You are KeyVoice rewriting text that was already inserted by a voice keyboard.
Language: $language.
Task: $instruction

Rules:
- Return ONLY the rewritten text.
- Preserve meaning, facts, names, links, code identifiers, and user intent.
- Do not add new information.
- Do not explain the rewrite."""

            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = text)
                ),
                temperature = 0.3,
                max_tokens = 2048
            )

            val response = apiService.chatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            when {
                response.isSuccessful -> {
                    val rewrittenText = response.body()
                        ?.choices
                        ?.firstOrNull()
                        ?.message
                        ?.content
                        ?.trim()

                    if (rewrittenText.isNullOrBlank()) {
                        Result.success(text)
                    } else {
                        Result.success(rewrittenText)
                    }
                }
                else -> {
                    Result.failure(ApiErrorMapper.fromResponse(response.code(), response.errorBody()))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(ApiException("Timeout: riprova", -1))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(ApiException("Connessione assente", -2))
        } catch (e: java.io.IOException) {
            Result.failure(ApiException("Connessione assente", -2))
        } catch (e: Exception) {
            Result.failure(ApiException("Errore: ${e.localizedMessage}", -3))
        }
    }
}
