package com.keyvoice.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for Groq API endpoints.
 * Covers both Whisper (audio transcription) and Chat Completions (LLM refinement).
 */
interface GroqApiService {

    /**
     * Phase 1: Audio transcription via Whisper.
     * POST https://api.groq.com/openai/v1/audio/transcriptions
     */
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("prompt") prompt: RequestBody? = null,
        @Part("response_format") responseFormat: RequestBody? = null
    ): Response<ResponseBody>

    /**
     * Phase 2: Text refinement via Chat Completions.
     * POST https://api.groq.com/openai/v1/chat/completions
     */
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}

// ── Data classes for Chat Completions ──

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val max_tokens: Int = 2048
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val id: String?,
    val choices: List<ChatChoice>?,
    val error: ApiError? = null
)

data class ChatChoice(
    val index: Int,
    val message: ChatMessage?,
    val finish_reason: String?
)

data class ApiError(
    val message: String?,
    val type: String?,
    val code: String?
)
