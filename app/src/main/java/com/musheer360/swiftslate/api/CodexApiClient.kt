package com.musheer360.swiftslate.api

import com.musheer360.swiftslate.model.CodexApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * Client for the community Codex API endpoint credited to @nepcodexcc.
 *
 * The endpoint is keyless and returns JSON from a GET request. SwiftSlate still wraps
 * the user's text with the same prompt-injection-resistant envelope used by the official
 * providers, so custom commands behave consistently across providers.
 */
class CodexApiClient {

    companion object {
        const val BASE_URL = "https://chatbot.codexapi.workers.dev"
        private const val MODELS_ENDPOINT = "$BASE_URL/models"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.applyCodexHeaders()
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                val jsonResponse = JSONObject(response)
                val models = LinkedHashSet<String>()
                models.add(CodexApiModels.RANDOM_MODEL_ID)

                jsonResponse.optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        val id = if (obj != null) {
                            listOf(obj.optString("id"), obj.optString("name"), obj.optString("model"))
                                .firstOrNull { it.isNotBlank() }
                        } else {
                            arr.optString(i)
                        }
                        id?.trim()?.takeIf { it.isNotBlank() }?.let { models.add(it) }
                    }
                }
                jsonResponse.optJSONArray("models")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        val id = if (obj != null) {
                            listOf(obj.optString("id"), obj.optString("name"), obj.optString("model"))
                                .firstOrNull { it.isNotBlank() }
                        } else {
                            arr.optString(i)
                        }
                        id?.trim()?.takeIf { it.isNotBlank() }?.let { models.add(it) }
                    }
                }

                val supported = CodexApiModels.filterSupported(models)
                Result.success(supported.takeIf { it.size > 1 } ?: CodexApiModels.FALLBACK)
            } else {
                // The live /models route currently returns 403/404 depending on headers. Keep
                // Settings usable with the verified built-in list instead of surfacing failure.
                Result.success(CodexApiModels.FALLBACK)
            }
        } catch (_: Exception) {
            // Settings should remain usable even if the community endpoint is unavailable.
            Result.success(CodexApiModels.FALLBACK)
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
        val sanitizedModel = CodexApiModels.sanitize(model)
        var result = doGenerate(prompt, text, sanitizedModel, temperature)
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            delay(1500)
            result = doGenerate(prompt, text, sanitizedModel, temperature)
        }
        if (result.isFailure && sanitizedModel != CodexApiModels.RANDOM_MODEL_ID &&
            result.exceptionOrNull()?.message?.contains("empty response", ignoreCase = true) == true
        ) {
            // A community model can temporarily start returning {"answer":""}. Fall back to
            // the endpoint's random mode so the user's command still has a chance to complete.
            result = doGenerate(prompt, text, CodexApiModels.RANDOM_MODEL_ID, temperature)
        }
        result
    }

    private fun doGenerate(
        prompt: String,
        text: String,
        model: String,
        @Suppress("UNUSED_PARAMETER") temperature: Double
    ): Result<GenerateResult> {
        var connection: HttpURLConnection? = null
        return try {
            val requestPrompt = ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt + "\n\n" +
                ApiClientUtils.wrapUserText(text)
            val encodedPrompt = URLEncoder.encode(requestPrompt, "UTF-8")
            val sanitizedModel = CodexApiModels.sanitize(model)
            val url = if (sanitizedModel == CodexApiModels.RANDOM_MODEL_ID) {
                "$BASE_URL/?prompt=$encodedPrompt"
            } else {
                "$BASE_URL/?prompt=$encodedPrompt&model=" + URLEncoder.encode(sanitizedModel, "UTF-8")
            }

            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.applyCodexHeaders()
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                var resultText = extractGeneratedText(ApiClientUtils.readResponseBounded(connection))
                    .trim()
                resultText = ApiClientUtils.stripReasoningBlock(resultText)
                resultText = ApiClientUtils.stripMarkdownFences(resultText)

                if (resultText.isBlank()) {
                    Result.failure(Exception("Model returned empty response"))
                } else {
                    Result.success(GenerateResult(resultText))
                }
            } else if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
                val msg = if (retryAfter != null) "Rate limit exceeded, retry after ${retryAfter}s" else "Rate limit exceeded"
                Result.failure(ApiException(ApiError.RateLimit(msg, retryAfter), msg))
            } else if (responseCode == 413 || responseCode == 414) {
                Result.failure(ApiException(ApiError.RequestTooLarge("Request too large"), "Request too large"))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error (HTTP $responseCode)")
                val apiError = if (responseCode in 500..599) ApiError.ServerError(detail) else ApiError.Other(detail)
                Result.failure(ApiException(apiError, detail))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is ApiException -> e.apiError
                is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException ->
                    ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            if (e is ApiException) Result.failure(e) else Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }

    private fun HttpURLConnection.applyCodexHeaders() {
        setRequestProperty("Accept", "application/json, text/plain, */*")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        // The Cloudflare Worker currently blocks default Java/Android HTTP user agents with 403.
        // A normal mobile-browser UA matches successful manual tests and keeps Codex keyless.
        setRequestProperty("User-Agent", BROWSER_USER_AGENT)
    }

    private fun extractGeneratedText(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isBlank()) return ""
        return try {
            val json = JSONObject(trimmed)
            listOf("answer", "response", "content", "text")
                .firstNotNullOfOrNull { key -> json.optString(key, "").takeIf { it.isNotBlank() } }
                ?: json.optJSONObject("message")?.optString("content", "")?.takeIf { it.isNotBlank() }
                ?: json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.takeIf { it.isNotBlank() }
                ?: ""
        } catch (_: Exception) {
            trimmed
        }
    }
}
