package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import androidx.activity.ComponentActivity
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.agent.TurnOutcome
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import dev.phonecode.app.ui.components.MisulDialog
import dev.phonecode.app.ui.components.MisulDialogAction
import dev.phonecode.app.ui.components.MisulField
import dev.phonecode.app.ui.components.MisulGroup
import dev.phonecode.app.ui.components.MisulNavigationRow
import dev.phonecode.app.ui.components.MisulSelectionRow
import dev.phonecode.app.ui.components.MisulSearchField
import dev.phonecode.app.ui.components.MisulToggleRow
import dev.phonecode.app.ui.theme.PhoneCodeTheme

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

    @Test
    fun workspaceDrawerIsAnExtractedImmutableSurfaceWithDedicatedMenus() {
        val drawerRoot = java.io.File("src/main/kotlin/dev/phonecode/app/ui/drawer")
        val app = java.io.File("src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt").readText()
        val drawer = java.io.File(drawerRoot, "WorkspaceDrawer.kt")
        val menus = java.io.File(drawerRoot, "WorkspaceDrawerMenus.kt")

        assertTrue(drawer.isFile)
        assertTrue(menus.isFile)
        assertTrue(app.contains("WorkspaceDrawer("))
        assertFalse(app.contains("private fun Sidebar("))
        assertTrue(drawer.readText().contains("data class WorkspaceDrawerState"))
        assertFalse(drawer.readText().contains("ChatViewModel"))
        assertTrue(drawer.readText().contains("testTag(\"workspace-drawer\")"))
        assertTrue(menus.readText().contains("fun WorkspaceDrawerMenu("))
        assertTrue(menus.readText().contains("destructive = true"))
    }

    @Test
    fun settingsFoundationExtractsTheShellAndExactRootGroups() {
        val settingsRoot = java.io.File("src/main/kotlin/dev/phonecode/app/ui/settings")
        val screen = java.io.File(settingsRoot, "SettingsScreen.kt").readText()
        val components = java.io.File(settingsRoot, "SettingsComponents.kt")
        val home = java.io.File(settingsRoot, "SettingsHome.kt")
        val workspace = java.io.File(settingsRoot, "WorkspaceSettings.kt")
        val data = java.io.File(settingsRoot, "DataSettings.kt")

        assertTrue(components.isFile)
        assertTrue(home.isFile)
        assertTrue(workspace.isFile)
        assertTrue(data.isFile)
        assertFalse(screen.contains("private fun Page("))
        assertFalse(screen.contains("internal fun HomePage("))
        assertTrue(components.readText().contains("fun SettingsPageShell("))
        assertTrue(components.readText().contains("StretchSyncedScrollChrome"))
        assertTrue(components.readText().contains("testTag(\"settings-page-shell\")"))
        val homeSource = home.readText()
        listOf("Agent", "Capabilities", "Workspace", "App").forEach { group ->
            assertTrue("Missing settings root group: $group", homeSource.contains("MisulGroup") && homeSource.contains("\"$group\""))
        }
        assertFalse(homeSource.contains("Memory"))
        assertTrue(homeSource.contains("showDivider = false"))
        assertTrue(homeSource.contains("SettingsNavigationRow"))
        assertTrue(workspace.readText().contains("Link a folder"))
        assertTrue(data.readText().contains("Export chats and settings"))
        assertTrue(data.readText().contains("Copy config directory path"))
    }

    @Test
    fun chatFeedbackAndDecisionOverlaysUseDedicatedScreenRoles() {
        val root = java.io.File("src/main/kotlin/dev/phonecode/app/ui/chat")
        val screen = java.io.File(root, "ChatScreen.kt").readText()
        val status = java.io.File(root, "ChatStatus.kt")
        val overlays = java.io.File(root, "ChatOverlays.kt")
        val turns = java.io.File(root, "ChatTurn.kt")

        assertTrue(status.isFile)
        assertTrue(overlays.isFile)
        assertTrue(turns.isFile)
        assertTrue(screen.contains("ChatStatus("))
        assertTrue(screen.contains("ChatOverlays("))
        assertTrue(turns.readText().contains("fun AssistantTurn("))
        assertTrue(turns.readText().contains("fun ToolActivityView("))
        assertTrue(status.readText().contains("fun ChatStatus("))
        assertTrue(status.readText().contains("TurnOutcome.FAILED"))
        assertTrue(overlays.readText().contains("fun ChatOverlays("))
        assertTrue(overlays.readText().contains("ModalBottomSheet"))
        assertTrue(overlays.readText().contains("MisulDialogAction"))
        assertTrue(overlays.readText().contains("DialogProperties(usePlatformDefaultWidth = false)"))
    }

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
            UiTestSecureKeyStore.replaceWith(
                mapOf("anthropic" to "ui-smoke-key", "openai" to "ui-smoke-openai-key"),
            )
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
        compose.onNodeWithText("Reasoning").assertIsDisplayed()
        compose.onAllNodesWithText("Agent mode").assertCountEquals(0)
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
        compose.onNodeWithText("Models & providers").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Edit skill").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Skill instructions").fetchSemanticsNodes().isNotEmpty()
        }
        val draft = "Unsaved instruction."
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
            "Appearance",
            "Personalization",
            "Models & providers",
            "Agent tools",
            "MCP servers",
            "Skills",
            "Files & permissions",
            "Git",
            "Export & import",
        ).forEach { page ->
            compose.onNodeWithText(page).performClick()
            compose.onNodeWithContentDescription("Back").performClick()
        }
        // Provider detail page (toggles + per-model visibility).
        compose.onNodeWithText("Models & providers").performClick()
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
        val introOrder = compose.onNodeWithTag("approval-intro")
            .fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val riskOrder = compose.onNodeWithTag("approval-risk")
            .fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val detailsOrder = compose.onNodeWithTag("approval-details")
            .fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val actionsOrder = compose.onNodeWithTag("approval-actions")
            .fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        assertTrue(
            "Approval traversal must announce context, risk, details, then actions",
            introOrder < riskOrder && riskOrder < detailsOrder && detailsOrder < actionsOrder,
        )
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
        compose.onNodeWithContentDescription("Queue message").assertIsDisplayed()
        state.value = state.value.copy(isRunning = false)
    }

    @Test
    fun queuedFollowUpIsCheckpointedBeforeTheRunningTurnEnds() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val historyField = vm.javaClass.getDeclaredField("history").apply { isAccessible = true }
        historyField.set(
            vm,
            listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("Active prompt")))),
        )
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(ChatLine.User("Active prompt")),
        )
        val storeField = vm.javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        val store = storeField.get(vm) as SessionStore

        assertTrue(vm.send("Persist this follow-up"))
        compose.waitUntil(5_000) {
            store.load(state.value.currentSessionId)?.queuedMessages ==
                listOf("Persist this follow-up")
        }
    }

    @Test
    fun unsentFollowUpsBlockAReplacementTurnUntilRecovered() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            isRunning = false,
            queued = listOf("Recover this first"),
            selected = state.value.models.first { it.providerId == "openai" },
        )

        try {
            assertFalse(vm.send("Do something else"))
            assertEquals(
                "Restore or clear the unsent follow-ups before sending another message.",
                state.value.error,
            )
            assertEquals(listOf("Recover this first"), state.value.queued)
        } finally {
            if (state.value.isRunning) vm.cancel()
        }
    }

    @Test
    fun failedTurnOffersRetryAtTheFailedPrompt() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            lines = listOf(ChatLine.User("Try the request")),
            error = "The connection failed.",
            interruptedTurn = false,
            turnOutcome = TurnOutcome.FAILED,
        )

        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun unsentFollowUpsCanBeRestoredWithoutLosingTheirText() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            isRunning = false,
            queued = listOf("Keep the first follow-up", "Keep the second follow-up"),
        )

        compose.onNodeWithText("2 unsent follow-ups").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithContentDescription("Message")
            .assertTextEquals("Keep the first follow-up\n\nKeep the second follow-up")
        assertTrue(state.value.queued.isEmpty())
    }

    @Test
    fun stoppingKeepsQueuedFollowUpsAndMarksPartialOutput() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            isRunning = true,
            queued = listOf("Do not lose this"),
        )

        app.chatViewModel.cancel()
        compose.waitForIdle()

        assertEquals(listOf("Do not lose this"), state.value.queued)
        compose.onNodeWithText("Turn stopped · Partial output may be incomplete.").assertIsDisplayed()
        compose.onNodeWithText("1 unsent follow-up").assertIsDisplayed()
    }

    @Test
    fun stoppingWhileApprovalIsPendingMarksTheToolStopped() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(
                ChatLine.ToolActivity(
                    id = "approval-cancel",
                    name = "write",
                    status = ToolStatus.AWAITING_APPROVAL,
                    detail = "Write release notes",
                ),
            ),
            pendingPermission = PermissionRequest(
                tool = "write",
                summary = "Write release notes",
            ),
        )

        app.chatViewModel.cancel()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Write stopped, Stopped").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Waiting to write file, Awaiting approval")
            .assertCountEquals(0)
    }

    @Test
    fun toolLifecycleIsAnnouncedAndItsDetailsAreDiscoverable() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            lines = listOf(
                ChatLine.ToolActivity(
                    id = "command-1",
                    name = "bash",
                    status = ToolStatus.RUNNING,
                    detail = "./gradlew test",
                ),
            ),
        )

        compose.onNodeWithContentDescription("Running command, Running").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open tool details").assertIsDisplayed()
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

        compose.onNodeWithContentDescription("Send safety feedback").performClick()
        compose.onNodeWithText("Hate").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Optional report details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel report").performClick()
    }

    @Test
    fun safetyFeedbackDraftSurvivesActivityRecreation() {
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

        compose.onNodeWithContentDescription("Send safety feedback").performClick()
        compose.onNodeWithText("Privacy").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Optional report details")
            .performTextInput("The response exposed private information.")

        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Send safety feedback").assertIsDisplayed()
        compose.onNodeWithText("Privacy").assertIsSelected()
        compose.onNodeWithContentDescription("Optional report details")
            .assertTextContains("The response exposed private information.")
        compose.onNodeWithContentDescription("Cancel report").performClick()
    }

    @Test
    fun reportFailureStaysVisibleBesideTheSendAction() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(lines = listOf(ChatLine.Assistant("A response to review.")))
        replaceReportClient(
            vm,
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Unavailable")
                    .body("{}".toResponseBody())
                    .build()
            }.build(),
        )

        try {
            compose.onNodeWithContentDescription("Send safety feedback").performClick()
            compose.onNodeWithText("Hate").performClick()
            compose.onNodeWithText("Send").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Reporting is temporarily unavailable. Try again later.")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Reporting is temporarily unavailable. Try again later.")
                .assertIsDisplayed()
            compose.onNodeWithText("Send").assertIsDisplayed()
        } finally {
            restoreReportClient(vm)
        }
    }

    @Test
    fun reportCannotDismissWhileSubmissionOutcomeIsUnknown() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(lines = listOf(ChatLine.Assistant("A response to review.")))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        replaceReportClient(
            vm,
            OkHttpClient.Builder().addInterceptor { chain ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(202)
                    .message("Accepted")
                    .body("""{"id":"report-test"}""".toResponseBody())
                    .build()
            }.build(),
        )

        try {
            compose.onNodeWithContentDescription("Send safety feedback").performClick()
            compose.onNodeWithText("Hate").performClick()
            compose.onNodeWithText("Send").performClick()
            assertTrue(started.await(5, TimeUnit.SECONDS))

            compose.onNodeWithContentDescription("Cancel report").assertIsNotEnabled()
            compose.onNodeWithText("Send safety feedback").assertIsDisplayed()
            compose.onNodeWithText("Sending…").assertIsDisplayed()
            compose.onNodeWithContentDescription("Feedback submission in progress").assertIsDisplayed()
        } finally {
            release.countDown()
            restoreReportClient(vm)
        }
    }

    @Test
    fun inFlightSafetyFeedbackSurvivesActivityRecreationWithoutDuplicateSubmission() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(lines = listOf(ChatLine.Assistant("A response to review.")))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val acceptedCount = AtomicInteger()
        replaceReportClient(
            vm,
            OkHttpClient.Builder().addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                acceptedCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(202)
                    .message("Accepted")
                    .body("""{"id":"report-$requestNumber"}""".toResponseBody())
                    .build()
            }.build(),
        )

        try {
            compose.onNodeWithContentDescription("Send safety feedback").performClick()
            compose.onNodeWithText("Hate").performClick()
            compose.onNodeWithText("Send").performClick()
            assertTrue(started.await(5, TimeUnit.SECONDS))

            compose.activityRule.scenario.recreate()
            compose.waitForIdle()

            compose.onNodeWithText("Sending…").assertIsDisplayed()
            compose.onNodeWithContentDescription("Feedback submission in progress").assertIsDisplayed()
            compose.onAllNodesWithText("Send").assertCountEquals(0)
            assertEquals(1, requestCount.get())

            release.countDown()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Feedback sent").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Feedback sent").assertIsDisplayed()
            assertEquals(1, requestCount.get())
            assertEquals(1, acceptedCount.get())
        } finally {
            release.countDown()
            restoreReportClient(vm)
        }
    }

    private var originalReportClient: OkHttpClient? = null

    private fun replaceReportClient(vm: Any, client: OkHttpClient) {
        vm.javaClass.getDeclaredField("reportHttp").apply {
            isAccessible = true
            if (originalReportClient == null) originalReportClient = get(vm) as OkHttpClient
            set(vm, client)
        }
    }

    private fun restoreReportClient(vm: Any) {
        val original = originalReportClient ?: return
        vm.javaClass.getDeclaredField("reportHttp").apply {
            isAccessible = true
            set(vm, original)
        }
        originalReportClient = null
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
    fun settingsSeparateInputPreferencesFromApprovalPolicy() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Personalization").performClick()
        compose.onNodeWithText("Message input").assertIsDisplayed()
        compose.onNodeWithText("Send on Enter").assertIsDisplayed()
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
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithText("Allow changes automatically").assertIsSelected()
            }.isSuccess
        }
    }

    @Test
    fun consequentialSettingsExplainEmptyAndInvalidStates() {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Agent tools").performClick()
        compose.onNodeWithText("Read only").performClick()
        compose.onNodeWithContentDescription("Search tools").performTextInput("definitely-no-such-tool")
        compose.onNodeWithText("No tools match", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Server name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remote URL").assertIsDisplayed()
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
        compose.onNodeWithText("Models & providers").performClick()
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
        compose.onNodeWithText("Replace chats and settings from a backup", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Import from file").performClick()
        compose.onNodeWithText("Replace chats and settings?").assertIsDisplayed()
        compose.onNodeWithText("approval returns to Ask", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Export first").assertIsDisplayed()
        compose.onNodeWithText("Choose backup file").assertIsDisplayed()
    }

    @Test
    fun importLocksTransferControlsWhileReplacementIsInProgress() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Export & import").performClick()
        state.value = state.value.copy(sessionLoading = true)

        compose.onNodeWithText("Importing backup…").assertIsDisplayed()
        compose.onNodeWithText("Export chats and settings").assertIsNotEnabled()
        compose.onNodeWithText("Import from file").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
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
        compose.onNodeWithText("Create skill").assertIsDisplayed()
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("active", substring = true).assertIsDisplayed()
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
        compose.onNodeWithText("Models & providers").performClick()
        compose.onNodeWithText("ChatGPT").performScrollTo().performClick()

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

/** Focused Compose contract fixture kept beside the app-wide smoke coverage. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class UiSmokeComponentsTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule = compose

    @Test
    fun fieldPaddingTapFocusesTextInput() {
        compose.setContent {
            PhoneCodeTheme {
                MisulField(
                    value = "",
                    onValueChange = {},
                    label = "Workspace name",
                    placeholder = "Name",
                )
            }
        }

        compose.onNodeWithContentDescription("Workspace name").performTouchInput {
            click(Offset(8f, 8f))
        }
        assertEquals(
            true,
            compose.onNodeWithContentDescription("Workspace name").fetchSemanticsNode().config[SemanticsProperties.Focused],
        )
    }

    @Test
    fun searchIconAndPaddingTapsFocusTheirTextInputs() {
        compose.setContent {
            PhoneCodeTheme {
                Column {
                    MisulSearchField(value = "", onValueChange = {}, placeholder = "Search first")
                    MisulSearchField(value = "", onValueChange = {}, placeholder = "Search second")
                }
            }
        }

        compose.onRoot().performTouchInput { click(Offset(8f, 24f)) }
        assertEquals(
            true,
            compose.onNodeWithContentDescription("Search first").fetchSemanticsNode().config[SemanticsProperties.Focused],
        )
        compose.onRoot().performTouchInput {
            click(Offset(100f, 48.dp.value * compose.density.density + 8f))
        }
        assertEquals(
            true,
            compose.onNodeWithContentDescription("Search second").fetchSemanticsNode().config[SemanticsProperties.Focused],
        )
    }

    @Test
    fun focusedInteractionComponentsKeepWholeRowAndDialogSemantics() {
        compose.setContent {
            PhoneCodeTheme {
                val toggle = remember { mutableStateOf(false) }
                val field = remember { mutableStateOf("") }
                Column {
                    MisulGroup {
                        MisulNavigationRow(label = "Appearance", onClick = {})
                        MisulSelectionRow(label = "System", selected = true, onClick = {}, showDivider = false)
                    }
                    MisulToggleRow(
                        label = "Send on Enter",
                        checked = toggle.value,
                        onCheckedChange = { toggle.value = it },
                    )
                    MisulField(
                        value = field.value,
                        onValueChange = { field.value = it },
                        label = "Email",
                        error = "Email is required",
                    )
                    MisulDialog(
                        title = "Delete project?",
                        onDismissRequest = {},
                        body = {},
                        actions = {
                            MisulDialogAction(label = "Cancel", onClick = {}, primary = false)
                            MisulDialogAction(label = "Delete", onClick = {}, destructive = true)
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Appearance").assertHasClickAction()
        compose.onNodeWithText("System").assertIsDisplayed()
        compose.onAllNodesWithTag("misul-row-divider-Appearance").assertCountEquals(1)
        compose.onAllNodesWithTag("misul-row-divider-System").assertCountEquals(0)
        compose.onNodeWithContentDescription("Send on Enter").assertIsToggleable()
        val toggleBounds = compose.onNodeWithContentDescription("Send on Enter").fetchSemanticsNode().boundsInRoot
        assertTrue(toggleBounds.height >= 48.dp.value * compose.density.density)
        compose.onNodeWithText("Send on Enter").performClick()
        assertEquals(
            androidx.compose.ui.state.ToggleableState.On,
            compose.onNodeWithContentDescription("Send on Enter").fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
        compose.onNodeWithContentDescription("Send on Enter").performTouchInput {
            click(Offset(width - 24f, center.y))
        }
        assertEquals(
            androidx.compose.ui.state.ToggleableState.Off,
            compose.onNodeWithContentDescription("Send on Enter").fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
        compose.onNodeWithText("Email is required").assertIsDisplayed()
        assertEquals(
            "Email is required",
            compose.onNodeWithContentDescription("Email").fetchSemanticsNode().config[SemanticsProperties.Error],
        )
        compose.onNodeWithText("Delete").assertHasClickAction()
        assertEquals(
            "Destructive action",
            compose.onNodeWithText("Delete").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        val cancelBounds = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val deleteBounds = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertTrue(cancelBounds.height >= 48.dp.value * compose.density.density)
        assertTrue(deleteBounds.height >= 48.dp.value * compose.density.density)
        assertTrue(deleteBounds.width < 200.dp.value * compose.density.density)
    }
}
