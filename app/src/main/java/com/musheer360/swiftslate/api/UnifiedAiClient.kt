package com.musheer360.swiftslate.api

import kotlinx.coroutines.Dispatchers import kotlinx.coroutines.withContext import okhttp3.MediaType.Companion.toMediaTypeOrNull import okhttp3.OkHttpClient import okhttp3.Request import okhttp3.RequestBody.Companion.toRequestBody import org.json.JSONArray import org.json.JSONObject import kotlin.random.Random

/**

Unified AI Client for SwiftSlate

Supports multiple free AI APIs (Codex and unofficial Copilot)

Provides random AI model selection and optional API selection */ class UnifiedAiClient {

private val client = OkHttpClient()

private val codexBaseUrl = "https://chatbot.codexapi.workers.dev/" private val copilotBaseUrl = "https://copilot-api-delta.vercel.app/"

private val codexModels = listOf( "gpt-5.2", "gpt-5.1", "gpt-5", "anthropic/claude-sonnet-4", "mercury-coder", "Olmo-3.1-32B-Instruct", "chatgpt-4o-latest", "google/gemini-2.5-pro-preview-05-06", "x-ai/grok-4", "deepseek-ai/deepseek-v3.2", "deepseek-ai/deepseek-v3.1-terminus", "deepseek-ai/deepseek-R1-0528", "o1-preview", "o3-mini", "qwen/qwen3.5-397b-a17b", "qwen/qwen3-coder-480b-a35b-instruct", "moonshotai/kimi-k2.5", "moonshotai/kimi-k2-thinking", "moonshotai/kimi-k2-instruct-0905", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "meta/llama-3.1-405b-instruct", "meta/llama-4-maverick-17b-128e-instruct", "meta/llama-4-scout-17b-16e-instruct", "meta-llama-3.3-70b-instruct", "meta-llama-3.1-8b-instruct", "google/gemma-3-27b-it", "nvidia/nemotron-3-nano-30b-a3b", "qwen/qwq-32b", "qwen/qwen3-235b-a22b", "minimaxai/minimax-m2", "accounts/fireworks/models/glm-4p7", "meta-llama/Llama-3.1-8B-Instruct", "mistralai/mistral-large-3-675b-instruct-2512", "mistralai/magistral-small-2506", "mistralai/mistral-small-3.1-24b-instruct-2503", "mistralai/ministral-14b-instruct-2512" )

/**

Send a prompt to either Codex or Copilot randomly (or fixed)

@param prompt Text prompt from user

@param randomApi If true, randomly pick API. Else use Codex by default */ suspend fun sendPrompt(prompt: String, randomApi: Boolean = true): String = withContext(Dispatchers.IO) { return@withContext if (randomApi) { when (Random.nextInt(2)) { 0 -> callCodexApi(prompt) else -> callCopilotApi(prompt) } } else { callCodexApi(prompt) } }


/**

Pick a random model from Codex list */ private fun pickRandomCodexModel(): String = codexModels.random()


/**

Call Codex API and return response */ private fun callCodexApi(prompt: String): String { val model = pickRandomCodexModel() val url = "$codexBaseUrl?prompt=${prompt}&model=$model" val request = Request.Builder().url(url).build() val response = client.newCall(request).execute() return response.body?.string() ?: "No response from Codex API" }


/**

Call unofficial Copilot API and return response */ private fun callCopilotApi(prompt: String): String { val messageArray = JSONArray().apply { put(JSONObject().apply { put("role", "user") put("content", prompt) }) }

val jsonBody = JSONObject().apply { put("model", "copilot") put("messages", messageArray) }

val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()) val request = Request.Builder() .url(copilotBaseUrl + "v1/chat/completions") .post(requestBody) .build()

val response = client.newCall(request).execute() return if (response.isSuccessful) response.body?.string() ?: "No response from Copilot API" else "Error: ${response.code} ${response.message}" } }
