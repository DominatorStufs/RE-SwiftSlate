package com.musheer360.swiftslate.model

/**
 * Codex API model catalogue helpers.
 *
 * The community endpoint exposes an OpenAI-compatible `/v1/models` list, but that list also
 * contains models that are not allowed for public use or are already end-of-life. SwiftSlate
 * therefore filters Settings to the models that returned a non-empty answer in live checks, so
 * users do not get "request failed" for a bad selection. `random` means SwiftSlate randomly picks
 * one of those verified working models for the request.
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
        "Olmo-3.1-32B-Instruct",
        "chatgpt-4o-latest",
        "google/gemini-2.5-pro-preview-05-06",
        "x-ai/grok-4",
        "o1-preview",
        "o3-mini",
        "openai/gpt-oss-20b"
    )

    private val SUPPORTED_MODELS = FALLBACK.toSet()

    fun isSupported(model: String): Boolean = model in SUPPORTED_MODELS

    fun filterSupported(models: Iterable<String>): List<String> {
        val filtered = LinkedHashSet<String>()
        filtered.add(RANDOM_MODEL_ID)
        models.map { it.trim() }
            .filter { it.isNotEmpty() && isSupported(it) }
            .forEach { filtered.add(it) }
        return filtered.toList()
    }

    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && isSupported(trimmed)) trimmed else DEFAULT
    }

    fun requestModel(value: String?): String {
        val sanitized = sanitize(value)
        return if (sanitized == RANDOM_MODEL_ID) FALLBACK.drop(1).random() else sanitized
    }

    fun displayName(model: String): String =
        if (model == RANDOM_MODEL_ID) "Random (Any Model)" else model
}
