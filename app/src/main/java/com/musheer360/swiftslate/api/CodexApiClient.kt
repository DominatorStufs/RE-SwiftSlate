package com.musheer360.swiftslate.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class CodexApiClient {

    companion object {
        private const val BASE_URL = "https://chatbot.codexapi.workers.dev"
        private const val MODELS_ENDPOINT = "$BASE_URL/models"
        const val RANDOM_MODEL_ID = "random"

        val FALLBACK_MODELS = listOf(
            RANDOM_MODEL_ID,
            "gpt-5.2",
            "gpt-5.1",
            "gpt-5",
            "anthropic/claude-sonnet-4",
            "mercury-coder",
            "Olmo-3.1-32B-Instruct",
            "chatgpt-4o-latest",
            "google/gemini-2.5-pro-preview-05-06",
            "x-ai/grok-4",
            "deepseek-ai/deepseek-v3.2",
            "deepseek-ai/deepseek-v3.1-terminus",
            "deepseek-ai/deepseek-R1-0528",
            "o1-preview",
            "o3-mini",
            "qwen/qwen3.5-397b-a17b",
            "qwen/qwen3-coder-480b-a35b-instruct",
            "moonshotai/kimi-k2.5",
            "moonshotai/kimi-k2-thinking",
            "moonshotai/kimi-k2-instruct-0905",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "meta/llama-3.1-405b-instruct",
            "meta/llama-4-maverick-17b-128e-instruct",
            "meta/llama-4-scout-17b-16e-instruct",
            "meta-llama-3.3-70b-instruct",
            "meta-llama-3.1-8b-instruct",
            "google/gemma-3-27b-it",
            "nvidia/nemotron-3-nano-30b-a3b",
            "qwen/qwq-32b",
            "qwen/qwen3-235b-a22b",
            "minimaxai/minimax-m2",
            "accounts/fireworks/models/glm-4p7",
            "meta-llama/Llama-3.1-8B-Instruct",
            "mistralai/mistral-large-3-675b-instruct-2512",
            "mistralai/magistral-small-2506",
            "mistralai/mistral-small-3.1-24b-instruct-2503",
            "mistralai/ministral-14b-instruct-2512"
        )
    }

    suspend fun getAvailableModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                val jsonResponse = JSONObject(response)
                val dataArray = jsonResponse.optJSONArray("data")

                val models = mutableListOf<String>()
                models.add(RANDOM_MODEL_ID) // Random always first
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val model = dataArray.getJSONObject(i)
                        val id = model.optString("id", "")
                        if (id.isNotEmpty()) models.add(id)
                    }
                }
                if (models.size <= 1) Result.success(FALLBACK_MODELS)
                else Result.success(models)
            } else {
                Result.success(FALLBACK_MODELS)
            }
        } catch (e: Exception) {
            Result.success(FALLBACK_MODELS)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun validateKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream?.use { stream ->
                    val buf = ByteArray(1024)
                    while (stream.read(buf) != -1) { /* drain */ }
                }
                Result.success("Valid")
            } else {
                Result.failure(Exception("Invalid or no API key required"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun generate(
        prompt: String,
        text: String,
        model: String,
        temperature: Double
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            // Random = no model param, API picks any
            val baseUrl = if (model == RANDOM_MODEL_ID || model.isBlank()) {
                "$BASE_URL/?prompt=$encodedText"
            } else {
                val safeModel = model.replace(Regex("[^a-zA-Z0-9._\\-/: ]"), "")
                "$BASE_URL/?prompt=$encodedText&model=$safeModel"
            }

            connection = URL(baseUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                var resultText = response.trim()
                resultText = ApiClientUtils.stripMarkdownFences(resultText)
                Result.success(GenerateResult(resultText, false))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "API Error")
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException ->
                    ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }
}
