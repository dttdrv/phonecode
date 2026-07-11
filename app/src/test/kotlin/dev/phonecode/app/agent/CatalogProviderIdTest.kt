package dev.phonecode.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogProviderIdTest {
    @Test
    fun aliasesAppProviderIdsToTheirCatalogEntries() {
        assertEquals("opencode", catalogProviderId("opencode-zen"))
        assertEquals("openai", catalogProviderId("codex"))
        assertEquals("opencode-go", catalogProviderId("opencode-go"))
    }

    @Test
    fun offlineOpenCodeModelsUseApiModelIds() {
        val models = builtInModels().associate { "${it.providerId}/${it.modelId}" to it }

        assertEquals("Go · DeepSeek V4 Flash", models["opencode-go/deepseek-v4-flash"]?.label)
        assertEquals("Go · MiMo V2.5", models["opencode-go/mimo-v2.5"]?.label)
        assertEquals("Zen · Nemotron 3 Ultra (Free)", models["opencode-zen/nemotron-3-ultra-free"]?.label)
        assertEquals("ChatGPT · GPT-5.6 Sol", models["codex/gpt-5.6-sol"]?.label)
    }
}
