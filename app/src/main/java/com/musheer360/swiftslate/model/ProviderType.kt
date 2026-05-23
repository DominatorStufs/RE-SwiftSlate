package com.musheer360.swiftslate.model

object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val CODEX_API = "codex_api"
    const val COPILOT = "copilot"
    const val CUSTOM = "custom"

    private val VALID = setOf(GEMINI, GROQ, CODEX_API, COPILOT, CUSTOM)
    fun sanitize(value: String?): String = if (value in VALID) value!! else GEMINI
}
