package dev.phonecode.app.data

import dev.phonecode.provider.preset.AuthScheme
import dev.phonecode.provider.preset.ProviderPreset
import dev.phonecode.provider.preset.WireFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CustomModel(val name: String = "", val context: Long? = null)

@Serializable
data class CustomProvider(
    val name: String = "",
    val baseUrl: String = "",
    /** "openai" (default, OpenAI-compatible) or "anthropic". */
    val format: String = "openai",
    val models: Map<String, CustomModel> = emptyMap(),
)

@Serializable
data class ProvidersConfig(val provider: Map<String, CustomProvider> = emptyMap())

/**
 * Agent- and user-editable custom providers/models, stored as `providers.json` under the config dir
 * (same dir the agent already knows from the system prompt). The agent can add a provider/model by
 * editing this file with its file tools; the app reloads it into the catalog + model picker.
 */
class CustomProviderRepository(private val configDir: File) {
    private val file = File(configDir, "providers.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun load(): ProvidersConfig =
        if (file.exists()) runCatching { json.decodeFromString(ProvidersConfig.serializer(), file.readText()) }.getOrDefault(ProvidersConfig())
        else ProvidersConfig()

    fun save(config: ProvidersConfig) {
        configDir.mkdirs()
        file.writeText(json.encodeToString(ProvidersConfig.serializer(), config))
    }

    val path: String get() = file.absolutePath
}

/** Map a custom provider entry to a [ProviderPreset] the ProviderFactory can construct. */
fun CustomProvider.toPreset(id: String): ProviderPreset {
    val anthropic = format.equals("anthropic", ignoreCase = true)
    return ProviderPreset(
        id = id,
        displayName = name.ifBlank { id },
        baseUrl = baseUrl,
        wireFormat = if (anthropic) WireFormat.ANTHROPIC else WireFormat.OPENAI_COMPAT,
        authScheme = if (anthropic) AuthScheme.X_API_KEY else AuthScheme.BEARER,
        extraHeaders = if (anthropic) mapOf("anthropic-version" to "2023-06-01") else emptyMap(),
    )
}
