package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.PermissionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitForIdle()
    }

    @Test
    fun chatControlsOpenWithoutCrashing() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()

        // Model sheet opens from the composer's model pill (header is always visible; specific
        // model rows may sit below the sheet's scroll fold).
        compose.onNodeWithContentDescription("Switch model").performClick()
        compose.onNodeWithText("Model & reasoning").assertIsDisplayed()
        compose.onNodeWithText("Agent mode").assertIsDisplayed()
        compose.onAllNodesWithText("Build").onLast().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search models").performTextInput("definitely-no-such-model")
        compose.onNodeWithText("No models match", substring = true).assertIsDisplayed()
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
        compose.onAllNodesWithContentDescription("Message").assertCountEquals(0)
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
    fun approvalDialogExplainsTheActionAndPreventsDuplicateDecisions() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "bash",
                summary = "Run ./gradlew assembleRelease in the current project",
            ),
        )

        compose.onNodeWithText("Approve agent action?").assertIsDisplayed()
        compose.onNodeWithText("Run a command").assertIsDisplayed()
        compose.onNodeWithText("bash", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Run ./gradlew assembleRelease in the current project").assertIsDisplayed()
        compose.onNodeWithText("Review this action before it runs.", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Approve once").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        state.value = state.value.copy(pendingPermission = null)
        compose.waitForIdle()
        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "mcp_issue_tracker_delete_issue",
                summary = "Delete issue 184 from the connected tracker",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Run an MCP server action").assertIsDisplayed()
        compose.onNodeWithText("external service", substring = true).assertIsDisplayed()
        state.value = state.value.copy(pendingPermission = null)
    }

    @Test
    fun approvalActionsStayVisibleForLongContent() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "external_directory_" + "very_long_tool_name_".repeat(10),
                summary = "Read a deliberately long external path. ".repeat(80),
            ),
        )

        compose.onNodeWithText("Deny").assertIsDisplayed()
        compose.onNodeWithText("Approve once").assertIsDisplayed()
        compose.onNodeWithText("Section 1 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next section").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Section 2 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Previous section").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy full details").performScrollTo().assertIsDisplayed()
        state.value = state.value.copy(pendingPermission = null)
    }

    @Test
    fun runningTurnKeepsStopAndQueuedSendReachable() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(isRunning = true)

        compose.onNodeWithContentDescription("Message").performTextInput("Follow up")

        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        state.value = state.value.copy(isRunning = false)
    }

    @Test
    fun reportSelectionsExposeTheirState() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(lines = listOf(ChatLine.Assistant("A response to review.")))
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Report AI response").performClick()
        compose.onNodeWithText("Hate").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Optional report details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel report").performClick()
    }

    @Test
    fun failedRedoKeepsTheExistingTurnVisible() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            selected = null,
            lines = listOf(
                ChatLine.User("Keep this question"),
                ChatLine.Assistant("Keep this answer"),
            ),
        )

        app.chatViewModel.redo()

        assertEquals(
            listOf(
                ChatLine.User("Keep this question"),
                ChatLine.Assistant("Keep this answer"),
            ),
            state.value.lines,
        )
        assertEquals("Select a model first.", state.value.error)
    }

    @Test
    fun settingsSeparateAgentDefaultsFromApprovalPolicy() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("General").performClick()
        compose.onNodeWithText("Default agent mode").assertIsDisplayed()
        compose.onNodeWithText("Build").assertIsDisplayed()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Message input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Files & permissions").performClick()
        compose.onNodeWithText("Approval policy").assertIsDisplayed()
        compose.onNodeWithText("Ask before each change").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Allow changes automatically").assertIsDisplayed()
        compose.onNodeWithText("writes, commands, Git operations", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("enabled MCP servers", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow changes automatically").performClick()
        compose.onNodeWithText("Enable automatic approval?").assertIsDisplayed()
        compose.onNodeWithText("MCP actions", substring = true).assertIsDisplayed()
        compose.onNodeWithText("linked phone folders", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Enable automatic approval").performClick()
        compose.onNodeWithText("Allow changes automatically").assertIsSelected()
    }

    @Test
    fun consequentialSettingsExplainEmptyAndInvalidStates() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Agent tools").performClick()
        compose.onNodeWithContentDescription("Search tools").performTextInput("definitely-no-such-tool")
        compose.onNodeWithText("No tools match", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Name is required").assertIsDisplayed()
    }

    @Test
    fun manualGitCredentialsCollectBothRequiredValues() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Git").performClick()
        compose.onNodeWithText("Advanced Git settings").performClick()
        compose.onNodeWithText("Git username").assertIsDisplayed()
        compose.onNodeWithText("Manual access token").assertIsDisplayed()
        compose.onNodeWithText("Save manual credentials").assertIsDisplayed()
    }

    @Test
    fun providerKeysChangeOnlyAfterExplicitSave() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("Anthropic").performClick()

        compose.onNodeWithContentDescription("Anthropic API key").performTextInput("replacement-key")
        assertEquals("ui-smoke-key", app.chatViewModel.keyFor("anthropic"))
        compose.onNodeWithText("Save key").performClick()
        assertEquals("replacement-key", app.chatViewModel.keyFor("anthropic"))
    }

    @Test
    fun importExplainsReplacementAndRequiresConfirmation() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(error = "Import failed: damaged backup")

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Export & import").performClick()
        compose.onNodeWithText("Import failed: damaged backup").assertIsDisplayed()
        compose.onNodeWithText("Import replaces chats and settings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Import from a file").performClick()
        compose.onNodeWithText("Replace chats and settings?").assertIsDisplayed()
        compose.onNodeWithText("approval returns to Ask", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Export first").assertIsDisplayed()
        compose.onNodeWithText("Choose backup file").assertIsDisplayed()
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

    @Test
    fun chatGptDisconnectRequiresConfirmation() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(codexConnected = true)

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("ChatGPT").performClick()

        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect ChatGPT?").assertIsDisplayed()
        compose.onNodeWithText("Existing chats stay on this device.", substring = true).assertIsDisplayed()
        assertTrue(state.value.codexConnected)

        compose.onNodeWithText("Cancel").performClick()
        assertTrue(state.value.codexConnected)

        compose.onNodeWithText("Disconnect").performClick()
        compose.onAllNodesWithText("Disconnect").onLast().performClick()
        compose.waitForIdle()
        assertFalse(state.value.codexConnected)
    }

}
