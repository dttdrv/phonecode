package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
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
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class UiSmokeTest {

    private val skillFixture = """---
name: hot-skill
description: Hot reload fixture
---

Original instruction.
"""

    /** First-run onboarding would otherwise cover the app for every test. */
    private val seedSettings = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val filesDir = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "ui-smoke-key"))
            java.io.File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
            java.io.File(filesDir, "config/skills/hot-skill/SKILL.md").apply {
                parentFile?.mkdirs()
                writeText(skillFixture)
            }
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
        compose.onNodeWithText("Skip setup for now").performClick()
        compose.waitForIdle()
    }

    @Test
    fun chatControlsOpenWithoutCrashing() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Attach photo or file").assertIsDisplayed()

        // Model sheet opens from the composer's model pill (header is always visible; specific
        // model rows may sit below the sheet's scroll fold).
        compose.onNodeWithContentDescription("Switch model").performClick()
        compose.onNodeWithText("Model & reasoning").assertIsDisplayed()
        compose.onNodeWithText("Agent mode").assertIsDisplayed()
        compose.onAllNodesWithText("Build").onLast().assertIsDisplayed()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        val done = compose.onAllNodesWithText("Done")
        if (done.fetchSemanticsNodes().isNotEmpty()) done.onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitForIdle()

        // Context usage breakdown opens from the glanceable ring (moved out of the tools menu).
        // Done last: this sheet has no in-content dismiss row, so we leave it open - the test only
        // proves it composes without crashing.
        compose.onNodeWithContentDescription("Context usage", substring = true).performClick()
        compose.onNodeWithText("Input").assertIsDisplayed()
    }

    @Test
    fun drawerOpensAndSettingsGearWorks() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Skills").assertIsDisplayed()
        compose.onNodeWithText("MCP").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Providers").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    @Test
    fun chatRuntimeSurvivesActivityRecreation() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        app.chatViewModel.surfaceError("Runtime retained")
        compose.onNodeWithText("Runtime retained").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText("Runtime retained").assertIsDisplayed()
        app.chatViewModel.clearError()
    }

    @Test
    fun composerDraftDoesNotCrossSessions() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Message").performTextInput("Session one draft")
        compose.onNodeWithContentDescription("Message").assertTextEquals("Session one draft")

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Message").assertTextEquals("")
    }

    @Test
    fun mcpDraftSurvivesActivityRecreation() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithContentDescription("Server name").performTextInput("draft-server")
        compose.onNodeWithContentDescription("Remote URL").performTextInput("https://example.com/mcp")

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Server name").assertTextEquals("draft-server")
        compose.onNodeWithContentDescription("Remote URL").assertTextEquals("https://example.com/mcp")
    }

    @Test
    fun skillDraftSurvivesRecreationAndExternalDelete() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Skills").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("hot-skill").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("hot-skill").onFirst().performClick()
        compose.onNodeWithText("Edit skill").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Skill instructions").fetchSemanticsNodes().isNotEmpty()
        }
        val draft = skillFixture.replace("Original instruction.", "Unsaved instruction.")
        compose.onNodeWithContentDescription("Skill instructions").performTextReplacement(draft)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Skill instructions").assertTextEquals(draft)

        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val directory = java.io.File(app.filesDir, "config/skills/hot-skill")
        java.io.File(directory, "SKILL.md").delete()
        directory.delete()
        app.chatViewModel.refreshSkills()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("This skill was removed or renamed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Skill instructions").assertTextEquals(draft)
        compose.onNodeWithText("Copy draft").assertIsDisplayed()
    }

    @Test
    fun everySettingsPageOpensWithoutCrashing() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
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

    @Test
    fun skillsAndMcpExposeManagementControls() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Server name").assertIsDisplayed()
        compose.onNodeWithText("Remote URL").assertIsDisplayed()
        compose.onNodeWithText("HTTP headers").assertIsDisplayed()
        compose.onNodeWithText("Connection timeout").assertIsDisplayed()
        compose.onNodeWithText("Test").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Skills").performClick()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("Issues").assertIsDisplayed()
        compose.onNodeWithText("Skill files reload automatically", substring = true).assertIsDisplayed()
    }
}
