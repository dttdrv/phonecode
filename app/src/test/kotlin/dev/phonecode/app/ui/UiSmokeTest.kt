package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric smoke tests over the REAL app composition: launch PhoneCodeApp and press everything
 * the user presses. Any composition/click-time crash fails here with a JVM stack trace instead of
 * only surfacing on a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class UiSmokeTest {

    /** First-run onboarding would otherwise cover the app for every test. */
    private val seedSettings = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val filesDir = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>().filesDir
            java.io.File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(seedSettings).around(compose)

    /** The onboarded=true seed races the activity's async settings load under Robolectric - when
     *  the load wins and reads a missing file, the overlay appears anyway. Click through it. */
    private fun dismissOnboardingIfPresent() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.waitForIdle()
    }

    @Test
    fun chatControlsOpenWithoutCrashing() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Tools").performClick()
        compose.onNodeWithText("Upload").assertIsDisplayed()
        compose.onAllNodesWithText("Thinking").onLast().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Upload").performClick()
        compose.waitForIdle()

        // Model sheet opens from the composer's model pill (header is always visible; specific
        // model rows may sit below the sheet's scroll fold).
        compose.onNodeWithText("Claude Opus 4.8").performClick()
        compose.onNodeWithText("Model").assertIsDisplayed()
        // Re-selecting the current model closes the sheet (the last node is the sheet row; the
        // first is the composer pill underneath the scrim).
        compose.onAllNodesWithText("Claude Opus 4.8").onLast().performClick()
        compose.waitForIdle()

        // New chat.
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitForIdle()

        // Context usage breakdown opens from the glanceable ring (moved out of the tools menu).
        // Done last: this sheet has no in-content dismiss row, so we leave it open - the test only
        // proves it composes without crashing.
        compose.onNodeWithContentDescription("Context usage").performClick()
        compose.onNodeWithText("Input").assertIsDisplayed()
    }

    @Test
    fun drawerOpensAndSettingsGearWorks() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Providers").assertIsDisplayed()
    }

    @Test
    fun everySettingsPageOpensWithoutCrashing() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        listOf(
            "General",
            "Appearance",
            "Personalization",
            "Providers",
            "MCP servers",
            "Skills",
            "Git",
            "Export & import",
        ).forEach { page ->
            compose.onNodeWithText(page).performClick()
            compose.onNodeWithContentDescription("Back").performClick()
        }
        // Provider detail page (toggles + per-model visibility).
        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        // About + its document pages.
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Terms of Service").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Privacy Policy").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Open-source licenses").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
    }
}
