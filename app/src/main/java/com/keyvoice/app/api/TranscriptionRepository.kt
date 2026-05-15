package com.keyvoice.app.api

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import com.keyvoice.app.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 1: Transcribes audio files using Groq's Whisper API.
 */
class TranscriptionRepository {

    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/"
        private const val WHISPER_MODEL = "whisper-large-v3"
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
     * Transcribes an audio file using Groq's Whisper API.
     *
     * @param audioFile The M4A audio file to transcribe
     * @param apiKey The Groq API key
     * @param language Optional ISO 639-1 language code (null for auto-detect)
     * @return Result containing the raw transcribed text
     */
    suspend fun transcribe(
        audioFile: File,
        apiKey: String,
        model: String,
        language: String? = null,
        prompt: String? = null
    ): Result<String> {
        return try {
            // Build multipart file part
            val filePart = MultipartBody.Part.createFormData(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            )

            // Build model part
            val modelPart = model.toRequestBody("text/plain".toMediaTypeOrNull())

            // Build optional language part
            val languagePart = language?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Build optional prompt part (vocabulary)
            val promptPart = if (!prompt.isNullOrBlank()) {
                prompt.toRequestBody("text/plain".toMediaTypeOrNull())
            } else null

            // Build response format part (plain text)
            val formatPart = "text".toRequestBody("text/plain".toMediaTypeOrNull())

            // Make API call
            val response = apiService.transcribeAudio(
                authorization = "Bearer $apiKey",
                file = filePart,
                model = modelPart,
                language = languagePart,
                prompt = promptPart,
                responseFormat = formatPart
            )

            when {
                response.isSuccessful -> {
                    val text = response.body()?.string()?.trim()
                    if (text.isNullOrBlank()) {
                        Result.failure(ApiException("Empty transcription result", 0))
                    } else {
                        Result.success(text)
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

/** Custom exception for API errors with HTTP status code */
class ApiException(message: String, val httpCode: Int) : Exception(message)
