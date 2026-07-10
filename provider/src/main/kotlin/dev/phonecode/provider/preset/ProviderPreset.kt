package dev.phonecode.provider.preset

import java.net.URI

/** Which wire format a provider speaks. Drives endpoint path and body shape. */
enum class WireFormat { OPENAI_COMPAT, ANTHROPIC, OPENAI_RESPONSES }

/** How the API key is attached. BEARER → `Authorization: Bearer k`; X_API_KEY → `x-api-key: k`. */
enum class AuthScheme { BEARER, X_API_KEY }

/**
 * A built-in or user-defined provider endpoint. The API key is injected at
 * construction time (from the Keystore on Android), never stored here.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val wireFormat: WireFormat,
    val authScheme: AuthScheme,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun withCatalogApi(api: String?): ProviderPreset {
        if (api.isNullOrBlank()) return this
        val trusted = runCatching {
            val current = URI(baseUrl)
            val update = URI(api)
            update.scheme == "https" && current.host.equals(update.host, ignoreCase = true)
        }.getOrDefault(false)
        return if (trusted) copy(baseUrl = api.trimEnd('/')) else this
    }
}

/** The four MVP providers. OpenCode Go is modeled as a Zen-style OPENAI_COMPAT preset if needed. */
object BuiltInPresets {
    val openai = ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    val anthropic = ProviderPreset(
        id = "anthropic",
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        wireFormat = WireFormat.ANTHROPIC,
        authScheme = AuthScheme.X_API_KEY,
        extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
    )

    val openrouter = ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
        extraHeaders = mapOf("HTTP-Referer" to "https://phonecode.app", "X-Title" to "PhoneCode"),
    )

    val opencodeZen = ProviderPreset(
        id = "opencode-zen",
        displayName = "OpenCode Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    // Additional providers - all OpenAI-compatible (Gemini via its OpenAI-compat endpoint), Bearer auth.
    val google = ProviderPreset(
        id = "google",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    val xai = ProviderPreset(
        id = "xai",
        displayName = "xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    val opencodeGo = ProviderPreset(
        id = "opencode-go",
        displayName = "OpenCode Go",
        // OpenCode Go's OpenAI-compatible API lives under /zen/go/v1; the old /go/v1 hit the
        // website 404 because Go is served beneath the Zen path, not at the site root.
        baseUrl = "https://opencode.ai/zen/go/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    val deepseek = ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    val mistral = ProviderPreset(
        id = "mistral",
        displayName = "Mistral",
        baseUrl = "https://api.mistral.ai/v1",
        wireFormat = WireFormat.OPENAI_COMPAT,
        authScheme = AuthScheme.BEARER,
    )

    /**
     * ChatGPT "Sign in with ChatGPT" (Codex). Speaks the Responses API against the ChatGPT backend; the key
     * is the OAuth access token (Bearer), and the per-user `chatgpt-account-id` header is attached at send
     * time (it can't be a static value here). Requires a paid ChatGPT plan.
     */
    val codex = ProviderPreset(
        id = "codex",
        displayName = "ChatGPT (Codex)",
        baseUrl = "https://chatgpt.com/backend-api/codex",
        wireFormat = WireFormat.OPENAI_RESPONSES,
        authScheme = AuthScheme.BEARER,
        extraHeaders = mapOf("OpenAI-Beta" to "responses=experimental", "originator" to "codex_cli_rs"),
    )

    // Together + Groq removed per user direction (round-3 feedback); OpenCode Go added.
    val all: List<ProviderPreset> = listOf(
        openai, anthropic, openrouter, opencodeZen, opencodeGo, google, xai, deepseek, mistral, codex,
    )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
