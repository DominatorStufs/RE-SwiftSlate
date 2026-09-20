package com.musheer360.swiftslate.api

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
 * Client for the community Unofficial Copilot API endpoint credited to @nepcodexcc.
 * It follows the OpenAI chat-completions response shape, but is keyless and fixed to the
 * endpoint's `copilot` model.
 */
class CopilotApiClient {

    companion object {
        const val BASE_URL = "https://copilot-api-delta.vercel.app"
        const val MODEL_ID = "copilot"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    suspend fun generate(
        prompt: String,
        text: String,
        temperature: Double
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var result = doGenerate(prompt, text, temperature)
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            delay(1500)
            result = doGenerate(prompt, text, temperature)
        }
        result
    }

    private fun doGenerate(
        prompt: String,
        text: String,
        temperature: Double
    ): Result<GenerateResult> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$BASE_URL/v1/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream, application/json, */*")
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val jsonBody = JSONObject().apply {
                put("model", MODEL_ID)
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
                // The public FastAPI docs/chat UI for this endpoint use streaming. Non-streaming
                // calls currently return HTTP 500 from the upstream Microsoft Copilot websocket.
                put("stream", true)
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                val parsed = extractGeneratedText(response)
                var resultText = parsed.first
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
            } else if (responseCode == 413) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Request too large"
                Result.failure(ApiException(ApiError.RequestTooLarge(detail), detail))
            } else if (responseCode == 400 || responseCode == 422) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.extractApiErrorMessage(errorBody)
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
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

}
