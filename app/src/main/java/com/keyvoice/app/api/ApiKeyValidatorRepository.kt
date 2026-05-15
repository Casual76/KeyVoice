package com.keyvoice.app.api

import com.keyvoice.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ApiKeyValidatorRepository {

    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/"
        private const val TIMEOUT_SECONDS = 15L
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

    suspend fun validate(apiKey: String): Result<Unit> {
        return try {
            val response = apiService.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                response.body()?.close()
                Result.success(Unit)
            } else {
                Result.failure(ApiErrorMapper.fromResponse(response.code(), response.errorBody()))
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
