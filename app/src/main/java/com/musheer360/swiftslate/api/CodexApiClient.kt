package com.musheer360.swiftslate.api

import com.musheer360.swiftslate.model.CodexApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Client for the community Codex API endpoint credited to @nepcodexcc.
 *
 * The endpoint is keyless and exposes OpenAI-compatible `/v1/models` and
 * `/v1/chat/completions` routes. SwiftSlate wraps the user's text with the same
 * prompt-injection-resistant envelope used by the official providers, so custom
 * commands behave consistently across providers.
 */
class CodexApiClient {

    companion object {
        const val BASE_URL = "https://chatbot.codexapi.workers.dev"
        private const val MODELS_ENDPOINT = "$BASE_URL/v1/models"
        private const val CHAT_COMPLETIONS_ENDPOINT = "$BASE_URL/v1/chat/completions"
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
                val models = CodexApiModels.filterSupported(ApiClientUtils.parseModelIds(response))
                Result.success(models.takeIf { it.size > 1 } ?: CodexApiModels.FALLBACK)
            } else {
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
            result.exceptionOrNull()?.message?.isCodexModelUnavailable() == true
        ) {
            // The public model list may contain models that later become disabled or EOL.
            // Fall back to a verified working random Codex model so the command can still run.
            result = doGenerate(prompt, text, CodexApiModels.RANDOM_MODEL_ID, temperature)
        }
        result
    }

    private fun doGenerate(
        prompt: String,
        text: String,
        model: String,
        temperature: Double
    ): Result<GenerateResult> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(CHAT_COMPLETIONS_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.applyCodexHeaders()
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val jsonBody = JSONObject().apply {
                put("model", CodexApiModels.requestModel(model))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", ApiClientUtils.wrapUserText(text))
                    })
                })
                put("temperature", temperature)
                // SwiftSlate waits for a complete replacement before writing text back, so the
                // non-streaming OpenAI-compatible response is simpler and works with this endpoint.
                put("stream", false)
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val parsed = extractGeneratedText(ApiClientUtils.readResponseBounded(connection))
                var resultText = parsed.first.trim()
                val finishReason = parsed.second
                resultText = ApiClientUtils.stripReasoningBlock(resultText)
                resultText = ApiClientUtils.stripMarkdownFences(resultText)

                if (finishReason == "content_filter") {
                    Result.failure(Exception("Response blocked by content filter"))
                } else if (resultText.isBlank()) {
                    Result.failure(Exception("Model returned empty response"))
                } else {
                    Result.success(GenerateResult(resultText, truncated = finishReason == "length"))
                }
            } else if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
                val msg = if (retryAfter != null) "Rate limit exceeded, retry after ${retryAfter}s" else "Rate limit exceeded"
                Result.failure(ApiException(ApiError.RateLimit(msg, retryAfter), msg))
            } else if (responseCode == 413 || responseCode == 414) {
                Result.failure(ApiException(ApiError.RequestTooLarge("Request too large"), "Request too large"))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = extractCodexErrorMessage(errorBody)
                    .ifEmpty { ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error (HTTP $responseCode)") }
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
        setRequestProperty("Accept", "application/json, text/event-stream, */*")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("User-Agent", BROWSER_USER_AGENT)
    }

    private fun extractGeneratedText(response: String): Pair<String, String> {
        val trimmed = response.trim()
        if (trimmed.isBlank()) return "" to ""

        if (trimmed.lineSequence().any { it.trimStart().startsWith("data:") }) {
            val out = StringBuilder()
            var finishReason = ""
            for (rawLine in trimmed.lineSequence()) {
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                try {
                    val chunk = JSONObject(payload)
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    finishReason = choice.optString("finish_reason", finishReason)
                    val delta = choice.optJSONObject("delta")
                    val message = choice.optJSONObject("message")
                    out.append(delta?.optString("content", "") ?: "")
                    if (delta == null) out.append(message?.optString("content", "") ?: "")
                } catch (_: Exception) {
                    // Ignore malformed keep-alive/comment lines in SSE streams.
                }
            }
            return out.toString() to finishReason
        }

        return try {
            val jsonResponse = JSONObject(trimmed)
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val finishReason = choice.optString("finish_reason", "")
                val message = choice.optJSONObject("message")
                val delta = choice.optJSONObject("delta")
                ((message?.optString("content", "") ?: delta?.optString("content", "") ?: "") to finishReason)
            } else {
                listOf("answer", "response", "content", "text")
                    .firstNotNullOfOrNull { key -> jsonResponse.optString(key, "").takeIf { it.isNotBlank() } }
                    .orEmpty() to ""
            }
        } catch (_: Exception) {
            trimmed to ""
        }
    }


    private fun extractCodexErrorMessage(errorBody: String): String {
        if (errorBody.isBlank()) return ""
        return try {
            val json = JSONObject(errorBody)
            val nested = ApiClientUtils.extractApiErrorMessage(errorBody)
            if (nested.isNotBlank()) nested else json.optString("message", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun String.isCodexModelUnavailable(): Boolean {
        val lower = lowercase()
        return lower.contains("empty response") ||
            lower.contains("model not allowed") ||
            lower.contains("not allowed") ||
            lower.contains("end of life") ||
            lower.contains("no longer available")
    }
}
