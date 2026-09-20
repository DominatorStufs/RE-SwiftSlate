package com.musheer360.swiftslate.model

/**
 * Codex API model catalogue helpers.
 *
 * The live endpoint exposes `/models`, but this fallback keeps Settings usable when the
 * community endpoint is down or unreachable. `random` means SwiftSlate omits the model
 * parameter and lets the API choose.
 */
object CodexApiModels {
    const val RANDOM_MODEL_ID = "random"
    val DEFAULT: String = RANDOM_MODEL_ID

    val FALLBACK: List<String> = listOf(
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

    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) DEFAULT else trimmed
    }

    fun displayName(model: String): String =
        if (model == RANDOM_MODEL_ID) "Random (Any Model)" else model
}
