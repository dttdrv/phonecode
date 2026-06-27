package dev.phonecode.provider.preset

import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInPresetsTest {

    @Test fun presetsWithExpectedWiring() {
        assertEquals(10, BuiltInPresets.all.size)

        // ChatGPT/Codex speaks the Responses API; the OAuth token is the Bearer key.
        val codex = BuiltInPresets.byId("codex")!!
        assertEquals("https://chatgpt.com/backend-api/codex", codex.baseUrl)
        assertEquals(WireFormat.OPENAI_RESPONSES, codex.wireFormat)
        assertEquals(AuthScheme.BEARER, codex.authScheme)

        val zen = BuiltInPresets.byId("opencode-zen")!!
        assertEquals("https://opencode.ai/zen/v1", zen.baseUrl)
        assertEquals(WireFormat.OPENAI_COMPAT, zen.wireFormat)
        assertEquals(AuthScheme.BEARER, zen.authScheme)

        val anthropic = BuiltInPresets.byId("anthropic")!!
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        assertEquals(WireFormat.ANTHROPIC, anthropic.wireFormat)
        assertEquals(AuthScheme.X_API_KEY, anthropic.authScheme)
        assertEquals("2023-06-01", anthropic.extraHeaders["anthropic-version"])

        assertEquals(WireFormat.OPENAI_COMPAT, BuiltInPresets.byId("openrouter")!!.wireFormat)
        assertEquals("https://api.openai.com/v1", BuiltInPresets.byId("openai")!!.baseUrl)

        // Added providers are all OpenAI-compatible + Bearer; Gemini uses its OpenAI-compat endpoint.
        val google = BuiltInPresets.byId("google")!!
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", google.baseUrl)
        assertEquals(WireFormat.OPENAI_COMPAT, google.wireFormat)
        assertEquals(AuthScheme.BEARER, google.authScheme)
        listOf("xai", "deepseek", "mistral", "opencode-go").forEach { id ->
            val p = BuiltInPresets.byId(id)!!
            assertEquals(WireFormat.OPENAI_COMPAT, p.wireFormat)
            assertEquals(AuthScheme.BEARER, p.authScheme)
        }
        // Removed per user direction.
        assertEquals(null, BuiltInPresets.byId("groq"))
        assertEquals(null, BuiltInPresets.byId("togetherai"))
    }
}
