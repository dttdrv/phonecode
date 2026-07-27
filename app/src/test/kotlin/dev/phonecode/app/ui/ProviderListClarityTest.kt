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

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(
                mapOf(
                    "anthropic" to "provider-list-test-key",
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
        compose.onNodeWithText("Providers").performClick()

        compose.onNodeWithText(
            "Switches control which providers appear in the model picker. " +
                "Provider setup and sign-in are managed inside each provider.",
        ).assertIsDisplayed()
        compose.onNodeWithText("API key saved · Shown in model picker").assertIsDisplayed()
        compose.onNodeWithText("Signed in with ChatGPT · Shown in model picker")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Setup required · Shown in model picker")
                .fetchSemanticsNodes().isNotEmpty(),
        )

        compose.onNodeWithContentDescription("Show Anthropic in model picker")
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("API key saved · Hidden from model picker")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissOnboardingIfPresent() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitForIdle()
    }
}
