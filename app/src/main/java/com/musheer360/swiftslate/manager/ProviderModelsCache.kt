package com.musheer360.swiftslate.manager

import com.musheer360.swiftslate.model.ProviderType

/**
 * Process-lifetime cache of the model lists fetched from provider `/models` endpoints.
 *
 * Session-only by design — never written to prefs — so a stale provider catalog
 * can never outlive this app process, matching how the Custom provider keeps its
 * fetched list in composition state. [Entry.attempted] records whether a real
 * fetch already ran this session (success or failure), so entering Settings
 * again never auto-refires it; only lack of an API key leaves [attempted] false
 * for key-required providers, letting the automatic fetch run once a first key is added.
 */
object ProviderModelsCache {
    data class Entry(val models: List<String>, val attempted: Boolean)

    @Volatile private var gemini: Entry? = null
    @Volatile private var groq: Entry? = null
    @Volatile private var codexApi: Entry? = null

    fun get(type: String): Entry? = when (type) {
        ProviderType.GEMINI -> gemini
        ProviderType.GROQ -> groq
        ProviderType.CODEX_API -> codexApi
        else -> null
    }

    fun put(type: String, entry: Entry) {
        when (type) {
            ProviderType.GEMINI -> gemini = entry
            ProviderType.GROQ -> groq = entry
            ProviderType.CODEX_API -> codexApi = entry
        }
    }
}
