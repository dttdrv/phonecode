package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class ProviderListClarityTest {

    @Test
    fun highDependencySettingsRenderersArePhysicallyExtracted() {
        val root = File("src/main/kotlin/dev/phonecode/app/ui/settings")
        val screen = File(root, "SettingsScreen.kt").readText()
        val expected = mapOf(
            "AgentToolsSettings.kt" to "AgentToolsPage",
            "ProviderSettings.kt" to "ProviderDetailPage",
            "McpSettings.kt" to "McpServerPage",
            "SkillSettings.kt" to "SkillEditorPage",
            "GitSettings.kt" to "GitPage",
        )

        expected.forEach { (fileName, renderer) ->
            val file = File(root, fileName)
            assertTrue("Missing extracted renderer file: $fileName", file.isFile)
            val source = file.readText()
            assertTrue("Missing renderer $renderer in $fileName", source.contains("fun $renderer("))
            assertFalse("$renderer should not remain in SettingsScreen", screen.contains("fun $renderer("))
            listOf("PcButton(", "PcIconButton(", "PcToggle(", "PcGroup(", "PcRow(", "PcField(", "PcSectionLabel(")
                .forEach { legacy ->
                    assertFalse("$fileName must use shared Misul components, not $legacy", source.contains(legacy))
                }
        }
        val agentTools = File(root, "AgentToolsSettings.kt").readText()
        assertTrue(agentTools.contains("PcMono"))
        assertTrue(agentTools.contains("Search tools"))
        assertTrue(agentTools.contains("stateDescription"))
        val providers = File(root, "ProviderSettings.kt").readText()
        assertTrue(providers.contains("MisulNavigationRow"))
        assertTrue(providers.contains("role = ActionRole.PRIMARY"))
    }

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(
                mapOf(
                    "anthropic" to "provider-list-test-key",
                    "openai" to "provider-list-openai-key",
                    "codex.access" to "provider-list-test-token",
                ),
            )
            File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSettings).around(compose)

    @Test
    fun providerRowsSeparateSetupFromModelPickerVisibility() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Models & providers").performClick()

        compose.onNodeWithText("API key saved").assertIsDisplayed()
        compose.onNodeWithText("Signed in with ChatGPT · Not yet available")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Setup required")
                .fetchSemanticsNodes().isNotEmpty(),
        )

        compose.onNodeWithText("API key saved · Not yet available")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun dismissOnboardingIfPresent() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitForIdle()
    }
}
