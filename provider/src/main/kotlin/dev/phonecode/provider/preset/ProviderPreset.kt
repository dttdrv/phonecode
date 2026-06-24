package dev.phonecode.provider.preset

/** Which wire format a provider speaks. Drives endpoint path and body shape. */
enum class WireFormat { OPENAI_COMPAT, ANTHROPIC }

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
)

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
        baseUrl = "https://opencode.ai/go/v1",
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

    // Together + Groq removed per user direction (round-3 feedback); OpenCode Go added.
    val all: List<ProviderPreset> = listOf(
        openai, anthropic, openrouter, opencodeZen, opencodeGo, google, xai, deepseek, mistral,
    )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
