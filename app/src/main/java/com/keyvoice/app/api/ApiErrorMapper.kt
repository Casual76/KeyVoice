package com.keyvoice.app.api

import okhttp3.ResponseBody
import org.json.JSONObject

object ApiErrorMapper {

    fun fromResponse(code: Int, errorBody: ResponseBody?): ApiException {
        val message = errorBody?.string()
            ?.let(::extractGroqMessage)
            ?.takeIf { it.isNotBlank() }

        return ApiException(message ?: fallbackMessage(code), code)
    }

    private fun extractGroqMessage(rawBody: String): String? {
        return runCatching {
            JSONObject(rawBody)
                .optJSONObject("error")
                ?.optString("message")
        }.getOrNull()
    }

    private fun fallbackMessage(code: Int): String {
        return when (code) {
            400 -> "Richiesta non valida"
            401 -> "API Key non valida"
            403 -> "Accesso negato"
            404 -> "Risorsa o modello non trovato"
            413 -> "File audio troppo grande"
            429 -> "Limite richieste raggiunto"
            in 500..599 -> "Servizio Groq temporaneamente non disponibile"
            else -> "Errore API: $code"
        }
    }
}
