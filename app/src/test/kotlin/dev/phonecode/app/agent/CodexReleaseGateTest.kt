package dev.phonecode.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReleaseGateTest {
    @Test
    fun releaseGateRejectsCodexButKeepsApiProviders() {
        assertFalse(providerAllowed("codex", codexOAuthEnabled = false))
        assertTrue(providerAllowed("openai", codexOAuthEnabled = false))
        assertTrue(providerAllowed("anthropic", codexOAuthEnabled = false))
        assertTrue(dev.phonecode.provider.preset.BuiltInPresets.anthropic.wireFormat.runsInMisul)
    }

    @Test
    fun alphaGateHidesCodexModelsEvenWhenOAuthIsAvailable() {
        assertFalse(builtInModels(codexOAuthEnabled = true).any { it.providerId == "codex" })
    }

    @Test
    fun releaseGateRemovesCodexModels() {
        assertFalse(builtInModels(codexOAuthEnabled = false).any { it.providerId == "codex" })
    }
}
