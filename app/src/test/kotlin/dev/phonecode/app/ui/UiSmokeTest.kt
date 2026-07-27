package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.agent.TurnOutcome
import dev.phonecode.agent.AgentEvent
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
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
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
    fun consumedFollowUpIsPersistedInHistoryBeforeLeavingTheQueue() {
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
            queued = listOf("Consumed follow-up"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val storeField = vm.javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        val store = storeField.get(vm) as SessionStore

        reduce.invoke(
            vm,
            AgentEvent.UserMessage("Consumed follow-up"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            generationField.getInt(vm),
        )
        compose.waitUntil(5_000) {
            val persisted = store.load(state.value.currentSessionId)
            persisted?.queuedMessages?.isEmpty() == true &&
                persisted.messages.lastOrNull()?.role == PersistedRole.USER &&
                persisted.messages.lastOrNull()?.parts ==
                listOf(PersistedPart.Text("Consumed follow-up"))
        }
    }

    @Test
    fun consumedFollowUpCheckpointsTheVisibleAssistantReplyBeforeIt() {
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
            queued = listOf("Consumed follow-up"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val generation = generationField.getInt(vm)
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val storeField = vm.javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        val store = storeField.get(vm) as SessionStore

        reduce.invoke(
            vm,
            AgentEvent.TextDelta("Visible assistant reply"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            generation,
        )
        reduce.invoke(
            vm,
            AgentEvent.UserMessage("Consumed follow-up"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            generation,
        )

        compose.waitUntil(5_000) {
            store.load(state.value.currentSessionId)?.queuedMessages?.isEmpty() == true
        }
        val persisted = store.load(state.value.currentSessionId)
        assertEquals(
            listOf(PersistedRole.USER, PersistedRole.ASSISTANT, PersistedRole.USER),
            persisted?.messages?.map { it.role },
        )
        assertEquals(
            listOf(PersistedPart.Text("Visible assistant reply")),
            persisted?.messages?.get(1)?.parts,
        )
    }

    @Test
    fun consumedFollowUpAfterCancellationCannotReactivateTheStoppedTurn() {
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
            queued = listOf("Do not consume after stop"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val admittedGeneration = generationField.getInt(vm)
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val storeField = vm.javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        val store = storeField.get(vm) as SessionStore

        vm.cancel()
        reduce.invoke(
            vm,
            AgentEvent.UserMessage("Do not consume after stop"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            admittedGeneration,
        )

        compose.waitUntil(5_000) {
            store.load(state.value.currentSessionId)?.turnOutcome == TurnOutcome.STOPPED.name
        }
        val persisted = store.load(state.value.currentSessionId)
        assertFalse(persisted?.activeTurn ?: true)
        assertEquals(listOf("Do not consume after stop"), persisted?.queuedMessages)
        assertEquals(listOf("Do not consume after stop"), state.value.queued)
    }

    @Test
    fun staleErrorAfterCancellationCannotOverwriteStoppedState() {
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
            queued = listOf("Keep after stop"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val admittedGeneration = generationField.getInt(vm)
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }

        vm.cancel()
        reduce.invoke(
            vm,
            AgentEvent.Error("stale failure"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            admittedGeneration,
        )

        compose.waitForIdle()
        assertFalse(state.value.isRunning)
        assertEquals(TurnOutcome.STOPPED, state.value.turnOutcome)
        assertEquals(listOf("Keep after stop"), state.value.queued)
    }

    @Test
    fun staleTurnCompleteAfterCancellationCannotOverwriteStoppedStateOrHistory() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val historyField = vm.javaClass.getDeclaredField("history").apply { isAccessible = true }
        val stoppedHistory = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("Active prompt"))))
        historyField.set(vm, stoppedHistory)
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(ChatLine.User("Active prompt")),
            queued = listOf("Keep after stop"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val admittedGeneration = generationField.getInt(vm)
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val staleHistory = stoppedHistory + ChatMessage(
            Role.ASSISTANT,
            listOf(MessagePart.Text("Stale completion")),
        )

        vm.cancel()
        reduce.invoke(
            vm,
            AgentEvent.TurnComplete(staleHistory),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            admittedGeneration,
        )

        compose.waitForIdle()
        assertFalse(state.value.isRunning)
        assertEquals(TurnOutcome.STOPPED, state.value.turnOutcome)
        assertEquals(listOf("Keep after stop"), state.value.queued)
        assertEquals(stoppedHistory, historyField.get(vm))
    }

    @Test
    fun staleHistoryCheckpointAfterCancellationCannotOverwriteStoppedHistory() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        val historyField = vm.javaClass.getDeclaredField("history").apply { isAccessible = true }
        val stoppedHistory = listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("Active prompt"))))
        historyField.set(vm, stoppedHistory)
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(ChatLine.User("Active prompt")),
            queued = listOf("Keep after stop"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val admittedGeneration = generationField.getInt(vm)
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val staleHistory = stoppedHistory + ChatMessage(
            Role.ASSISTANT,
            listOf(MessagePart.Text("Stale tool call checkpoint")),
        )

        vm.cancel()
        reduce.invoke(
            vm,
            AgentEvent.HistoryCheckpoint(staleHistory),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            admittedGeneration,
        )

        compose.waitForIdle()
        assertEquals(TurnOutcome.STOPPED, state.value.turnOutcome)
        assertEquals(listOf("Keep after stop"), state.value.queued)
        assertEquals(stoppedHistory, historyField.get(vm))
    }

    @Test
    fun identicalConsumedFollowUpIsPersistedAsADistinctUserMessage() {
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
            listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("Repeat this")))),
        )
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(ChatLine.User("Repeat this")),
            queued = listOf("Repeat this"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }
        val storeField = vm.javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        val store = storeField.get(vm) as SessionStore

        reduce.invoke(
            vm,
            AgentEvent.UserMessage("Repeat this"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            generationField.getInt(vm),
        )

        compose.waitUntil(5_000) {
            store.load(state.value.currentSessionId)?.queuedMessages?.isEmpty() == true
        }
        val persisted = store.load(state.value.currentSessionId)
        assertEquals(2, persisted?.messages?.count { it.role == PersistedRole.USER })
        assertEquals(
            listOf(PersistedPart.Text("Repeat this")),
            persisted?.messages?.lastOrNull()?.parts,
        )
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
            selected = state.value.models.first { it.providerId == "anthropic" },
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
    fun terminalAgentErrorKeepsQueuedFollowUpsRecoverable() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val stateField = vm.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(ChatLine.User("Start the turn")),
            queued = listOf("Recover this follow-up"),
        )
        val generationField = vm.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        val reduce = vm.javaClass.declaredMethods.single { it.name == "reduce" }.apply { isAccessible = true }

        reduce.invoke(
            vm,
            AgentEvent.Error("connection failed"),
            state.value.currentSessionId,
            state.value.currentProjectId,
            "anthropic",
            generationField.getInt(vm),
        )
        compose.waitForIdle()

        assertEquals(listOf("Recover this follow-up"), state.value.queued)
        compose.onNodeWithText("1 unsent follow-up").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithText("Retry").assertIsDisplayed()
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
        compose.onNodeWithText("Export chats & settings").assertIsNotEnabled()
        compose.onNodeWithText("Import from a file").assertIsNotEnabled()
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
