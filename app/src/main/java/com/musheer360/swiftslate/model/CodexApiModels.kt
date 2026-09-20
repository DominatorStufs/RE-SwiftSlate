package com.musheer360.swiftslate.model

/**
 * Codex API model catalogue helpers.
 *
 * The community endpoint's `/models` route is currently unreliable from Android/non-browser
 * clients, so SwiftSlate ships a known-good catalogue. This list was checked against the live
 * endpoint and only keeps models that returned a non-empty answer; models that returned an empty
 * `answer` are deliberately hidden so users do not get "request failed" for a bad selection.
 * `random` means SwiftSlate omits the model parameter and lets the API choose.
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

    fun displayName(model: String): String =
        if (model == RANDOM_MODEL_ID) "Random (Any Model)" else model
}
