package dev.phonecode.provider.http

import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.preset.ProviderPreset
import dev.phonecode.provider.preset.WireFormat
import okhttp3.OkHttpClient

/** Single construction seam: maps a [ProviderPreset] + key to the right [LlmProvider]. */
object ProviderFactory {
    fun create(preset: ProviderPreset, apiKey: String, client: OkHttpClient): LlmProvider =
        when (preset.wireFormat) {
            WireFormat.OPENAI_COMPAT -> OpenAiCompatProvider(preset, apiKey, client)
            WireFormat.ANTHROPIC -> AnthropicProvider(preset, apiKey, client)
            WireFormat.OPENAI_RESPONSES -> CodexProvider(preset, apiKey, client)
        }
}
