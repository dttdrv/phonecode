package dev.phonecode.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredModelSelectionTest {
    @Test
    fun activationMovesFromUnconfiguredDefaultToConfiguredProvider() {
        val anthropic = ModelOption("anthropic", "claude-sonnet", "Anthropic")
        val openAi = ModelOption("openai", "gpt-5", "OpenAI")

        val selected = configuredModelForActivation(
            models = listOf(anthropic, openAi),
            current = anthropic,
            providerConfigured = { it == "openai" },
        )

        assertEquals(openAi, selected)
    }

    @Test
    fun providerActivationSelectsAVisibleModelFromTheRequestedProvider() {
        val models = listOf(
            ModelOption("anthropic", "claude-sonnet", "Claude Sonnet"),
            ModelOption("openai", "gpt-5.6", "GPT-5.6"),
            ModelOption("openai", "gpt-5.5", "GPT-5.5"),
        )

        val selected = configuredModelForProviderActivation(
            models = models,
            providerId = "openai",
            hiddenModels = setOf("openai/gpt-5.6"),
        )

        assertEquals(ModelOption("openai", "gpt-5.5", "GPT-5.5"), selected)
    }

    @Test
    fun providerActivationReturnsNullWhenProviderHasNoVisibleModel() {
        val models = listOf(
            ModelOption("anthropic", "claude-sonnet", "Claude Sonnet"),
            ModelOption("openai", "gpt-5.6", "GPT-5.6"),
            ModelOption("openai", "gpt-5.5", "GPT-5.5"),
        )

        val selected = configuredModelForProviderActivation(
            models = models,
            providerId = "openai",
            hiddenModels = setOf("openai/gpt-5.6", "openai/gpt-5.5"),
        )

        assertNull(selected)
    }
}
