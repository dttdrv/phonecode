package dev.phonecode.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.SettingsOperation
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.agent.TurnOutcome
import dev.phonecode.app.agent.skillDeleteOperationKey
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.ProjectStore
import dev.phonecode.app.data.SessionMeta
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.SkillStatus
import dev.phonecode.app.ui.chat.ComposerActionTarget
import dev.phonecode.app.ui.chat.ComposerActionVisual
import dev.phonecode.app.ui.settings.SettingsNavigation
import dev.phonecode.app.ui.settings.SettingsRoute
import dev.phonecode.app.ui.settings.CustomProviderEditor
import dev.phonecode.app.ui.settings.GitPage
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.provider.domain.MessagePart
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import dev.phonecode.tools.UserOption
import dev.phonecode.tools.UserQuestion
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.McpServerSnapshot
import dev.phonecode.tools.mcp.McpToolDef
import dev.phonecode.tools.skills.SkillManifest

/**
 * The design feedback loop: renders the REAL app (same composition as UiSmokeTest) to PNGs in
 * app/screenshots/ via Roborazzi, so UI changes can be SEEN and judged without a device.
 * Runs as part of the normal unit-test task; screenshots are overwritten on every run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi", shadows = [UiTestSecureKeyStore::class])
class ScreenshotTest {

    @Test
    fun task10EvidenceMatrixContract() {
        val source = File("src/test/kotlin/dev/phonecode/app/ui/ScreenshotTest.kt").readText()
        assertTrue(source.contains("fun task10EvidenceStateMatrix()"))
        listOf(
            "41-task10-providers-clean",
            "41-task10-provider-key-success",
            "42-task10-provider-validation",
            "42-task10-provider-saving",
            "42-task10-provider-save-error",
            "42-task10-provider-dirty-discard",
            "42-task10-provider-config-error",
            "43-task10-provider-destructive",
            "44-task10-mcp-clean-connected",
            "44-task10-mcp-connected-error",
            "44-task10-mcp-connecting",
            "23-mcp-validation",
            "45-task10-mcp-dirty-discard",
            "45-task10-mcp-destructive",
            "35-skill-inventory-loading",
            "35-skill-active-detail",
            "46-task10-skill-delete-running",
            "46-task10-skill-operation-error",
            "47-task10-skill-dirty-discard",
            "47-task10-skill-validation-error",
            "48-task10-git-clean",
            "48-task10-git-device-code",
            "48-task10-git-browser-error",
            "49-task10-git-connected",
            "50-task10-git-save-error",
            "50-task10-git-dirty-discard",
            "50-task10-git-destructive",
        ).forEach { image -> assertTrue(source.contains("\"$image\"")) }
    }

    @Test
    fun appendedChatLinesEnterOnceAfterTheRestoredTimeline() {
        val screen = File("src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt").readText()

        assertTrue(screen.contains("ChatAppendTransitionTracker"))
        assertTrue(screen.contains("appendTransitions.observe("))
        assertTrue(screen.contains("messageEnter(entryMotion)"))
        assertTrue(screen.contains("ChatEntryMotion.START"))
        assertFalse(screen.contains("initialCount"))
        assertFalse(screen.contains("animatedIndices"))
        assertTrue(screen.contains("8.dp"))
        assertTrue(screen.contains("tween(180"))
    }

    /**
     * Seeds a realistic conversation BEFORE the activity launches, so launch-restore renders it.
     * Deliberately NO cleanup and NO wiping: deleting the seed between tests breaks later tests'
     * launch-restore in ways that only reproduce inside Robolectric's shared-worker filesystem
     * (verified empirically); leftover files are harmless - UiSmokeTest disambiguates instead.
     */
    private val seedSession = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(
                mapOf("anthropic" to "screenshot-fixture-key", "openai" to "screenshot-openai-key"),
            )
            // First-run onboarding would otherwise cover the app for every test.
            File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
            ProjectStore(File(filesDir, "projects.json")).replace(
                listOf(Project("project-screenshot", "PhoneCode", "folder-screenshot")),
            )
            SessionStore(File(filesDir, "sessions")).save(
                PersistedSession(
                    id = "session-screenshot",
                    title = "Settings dark mode",
                    updatedAt = System.currentTimeMillis() + 3_600_000,
                    projectId = "project-screenshot",
                    messages = listOf(
                        PersistedMessage(
                            PersistedRole.USER,
                            listOf(PersistedPart.Text("Add dark mode support to the settings screen")),
                        ),
                        PersistedMessage(
                            PersistedRole.ASSISTANT,
                            listOf(
                                PersistedPart.Reasoning("The theme is resolved in PhoneCodeApp from AppSettings.themeMode, so settings only needs to write the new mode and the whole tree recomposes."),
                                PersistedPart.ToolCall("c1", "read", """{"filePath":"ui/SettingsScreen.kt"}"""),
                                PersistedPart.ToolResult("c1", "object SettingsScreen ..."),
                                PersistedPart.ToolCall("c2", "edit", """{"filePath":"ui/SettingsScreen.kt"}"""),
                                PersistedPart.ToolResult("c2", "ok"),
                                PersistedPart.Text(
                                    "Dark mode is wired up. The appearance section now offers three modes:\n\n" +
                                        "```kotlin\nenum class ThemeMode { System, Light, Dark }\n```\n\n" +
                                        "The setting persists through `AppSettingsStore` and applies instantly - no restart needed.",
                                ),
                            ),
                        ),
                        PersistedMessage(
                            PersistedRole.USER,
                            listOf(PersistedPart.Text("Does it follow the system setting by default?")),
                        ),
                        PersistedMessage(
                            PersistedRole.ASSISTANT,
                            listOf(PersistedPart.Text("Yes - the default is **System**, which tracks the OS dark-mode toggle live. Light and Dark pin the theme regardless of the system.")),
                        ),
                    ),
                ),
            )
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSession).around(compose)

    private fun conversationVisible() =
        compose.onAllNodesWithText("Does it follow the system setting by default?")
            .fetchSemanticsNodes().isNotEmpty()

    /**
     * Gets the seeded conversation on screen. Launch-restore works only for the FIRST test in a
     * Robolectric process (its withContext(Main) hop posts to a stale main looper afterwards - a
     * Robolectric artifact, correct on device), so when it doesn't land we open the chat the way
     * a user would: drawer -> tap the session row (switchSession runs entirely on IO).
     */
    /** The onboarded=true seed races the activity's async settings load under Robolectric - when
     *  the load wins and reads a missing file, the overlay appears anyway. Click through it. */
    private fun dismissOnboardingIfPresent() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitForIdle()
    }

    private fun awaitConversation() {
        dismissOnboardingIfPresent()
        val restored = runCatching { compose.waitUntil(2_000) { conversationVisible() } }.isSuccess
        if (restored) return
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Settings dark mode").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            compose.onRoot().captureRoboImage("screenshots/debug-drawer-failure.png")
            throw e
        }
        compose.onNodeWithText("Settings dark mode").performClick()
        try {
            compose.waitUntil(15_000) { conversationVisible() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Capture what's actually on screen so the failure is diagnosable from the PNG.
            compose.onRoot().captureRoboImage("screenshots/debug-await-failure.png")
            throw e
        }
    }

    private fun settleAnimation(durationMillis: Long = 500L) {
        // Navigation starts from a posted composition. Drain that frame first; otherwise a clock
        // advance can happen before the transition exists and capture its initial, clipped frame.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(durationMillis)
        compose.waitForIdle()
    }

    private fun shoot(name: String) {
        settleAnimation()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun shootPage(name: String, title: String) {
        settleAnimation(2_000)
        val heading = compose.onAllNodes(hasText(title) and isHeading()).onLast()
        val back = compose.onNodeWithContentDescription("Back")
        heading.assertIsDisplayed()
        back.assertIsDisplayed()
        // A page transition can briefly leave both routes in the semantics tree. Capture the
        // shell that owns the asserted heading, not whichever shell happened to be composed last.
        // The bounds checks make the two known chrome artifacts fail before a PNG is written.
        assertTrue(heading.fetchSemanticsNode().boundsInRoot.top >= 0f)
        assertTrue(back.fetchSemanticsNode().boundsInRoot.top >= 0f)
        // Capture the full page shell when present. Both window and general-root captures can
        // crop a scrolled Settings subtree at the status-bar edge under Robolectric.
        val mergedPageShell = compose.onAllNodes(
            hasTestTag("settings-page-shell") and hasAnyDescendant(hasText(title) and isHeading()),
        )
        // Page-shell merging differs between the field-heavy MCP editor and the Markdown detail
        // page. Prefer the merged match, then use the unmerged tree only when it is absent.
        val pageShell = if (mergedPageShell.fetchSemanticsNodes().isNotEmpty()) {
            mergedPageShell
        } else {
            compose.onAllNodes(
                hasTestTag("settings-page-shell") and hasAnyDescendant(hasText(title) and isHeading()),
                useUnmergedTree = true,
            )
        }
        if (pageShell.fetchSemanticsNodes().isNotEmpty()) {
            pageShell.onLast().captureRoboImage("screenshots/$name.png")
        } else {
            compose.onRoot().captureRoboImage("screenshots/$name.png")
        }
    }

    /**
     * Roborazzi local-node and screen captures can each crop a Settings chrome layer. The Compose
     * root is the only surface that contains the verified fixed header and the route body together.
     */
    private fun shootFullPage(name: String, title: String) {
        settleAnimation(2_000)
        awaitStablePageChrome(title)
        // Direct Task 10 fixtures own one page shell. Capturing that renderer subtree avoids
        // Robolectric's stale app-root compositor layer while retaining header and scroll chrome.
        compose.onNodeWithTag("settings-page-shell").captureRoboImage("screenshots/$name.png")
    }

    /**
     * A node may be displayed while its NavHost transition still places it partly off-screen.
     * Wait for the fixed Settings chrome to occupy the current root before retaining evidence.
     */
    private fun awaitStablePageChrome(title: String) {
        val centerTolerance = 8f * compose.density.density
        repeat(8) {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val heading = compose.onAllNodes(hasText(title) and isHeading()).onLast()
            val back = compose.onNodeWithContentDescription("Back")
            heading.assertIsDisplayed()
            back.assertIsDisplayed()
            val headingBounds = heading.fetchSemanticsNode().boundsInRoot
            val backBounds = back.fetchSemanticsNode().boundsInRoot
            val rootCenter = (root.left + root.right) / 2f
            val headingCenter = (headingBounds.left + headingBounds.right) / 2f
            if (
                backBounds.left >= root.left &&
                backBounds.right <= root.right &&
                headingBounds.top >= root.top &&
                abs(headingCenter - rootCenter) <= centerTolerance
            ) return

            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val heading = compose.onAllNodes(hasText(title) and isHeading()).onLast()
        val back = compose.onNodeWithContentDescription("Back")
        val headingBounds = heading.fetchSemanticsNode().boundsInRoot
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val rootCenter = (root.left + root.right) / 2f
        val headingCenter = (headingBounds.left + headingBounds.right) / 2f
        assertTrue("Back is outside the captured root: $backBounds vs $root", backBounds.left >= root.left)
        assertTrue("Back is outside the captured root: $backBounds vs $root", backBounds.right <= root.right)
        assertTrue("Heading is above the captured root: $headingBounds vs $root", headingBounds.top >= root.top)
        assertTrue(
            "Heading is not centered in the captured root: $headingBounds vs $root",
            abs(headingCenter - rootCenter) <= 8f * compose.density.density,
        )
    }

    private fun shootScreen(name: String, visibleText: String) {
        settleAnimation()
        compose.onAllNodesWithText(visibleText).onLast().assertIsDisplayed()
        captureScreenRoboImage("screenshots/$name.png")
    }

    /** Dialog windows can keep Espresso's global-idle probe busy even after Compose has settled. */
    private fun shootDialog(name: String) {
        compose.mainClock.advanceTimeBy(500)
        // Each caller establishes the exact dialog state before capture. A semantics query here
        // re-enters Espresso's global-idle probe and hangs on a separate dialog window.
        captureScreenRoboImage("screenshots/$name.png")
    }

    @Suppress("UNCHECKED_CAST")
    private fun chatUiState(): MutableStateFlow<ChatUiState> {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        return app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
    }

    private fun assertComposerActionGeometry(contentDescription: String): Float {
        val bounds = compose.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot
        val target = ComposerActionTarget.value * compose.density.density
        assertEquals(target, bounds.width, 0.5f)
        assertEquals(target, bounds.height, 0.5f)
        assertEquals(40f, ComposerActionVisual.value, 0f)
        return bounds.bottom
    }

    @Test
    fun chatScreens() {
        awaitConversation()
        shoot("01-chat-conversation")

        compose.onNodeWithContentDescription("Switch model").performClick()
        shootScreen("03-model-picker", "Model & reasoning")
        compose.onAllNodesWithText("Done").onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        shoot("06-chat-empty")
    }

    @Test
    fun drawerAndSettings() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        shoot("07-drawer")

        compose.onNodeWithContentDescription("Settings").performClick()
        shootPage("08-settings-root", "Settings")
        compose.onNodeWithText("Models & providers").performClick()
        shootPage("09-settings-providers", "Providers")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Git").performClick()
        shootPage("10-settings-git", "Git")
    }

    @Test
    fun workspaceDrawerHasItsOwnScreenshotSurface() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithTag("workspace-drawer").assertIsDisplayed()
        shoot("drawer-workspace-surface")
    }

    @Test
    fun workspaceDrawerArchivedAndProjectMenuFixtures() {
        awaitConversation()
        val state = chatUiState()
        val original = state.value
        val project = Project(id = "drawer-project", name = "PhoneCode")
        val active = SessionMeta(
            id = "drawer-active",
            title = "Active implementation",
            preview = "Review the current interaction pass",
            updatedAt = System.currentTimeMillis(),
            projectId = project.id,
        )
        val archived = SessionMeta(
            id = "drawer-archived",
            title = "Archived release notes",
            updatedAt = System.currentTimeMillis() - 86_400_000L,
            archived = true,
        )
        try {
            state.value = original.copy(
                projects = listOf(project),
                sessions = listOf(active, archived),
                currentSessionId = active.id,
                currentProjectId = project.id,
            )
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithText("Archived").performScrollTo().performClick()
            shootScreen("drawer-archived-expanded", archived.title)

            compose.onNodeWithContentDescription("Project options").performClick()
            shootScreen("drawer-project-menu", "Delete project")
        } finally {
            state.value = original
        }
    }

    @Test
    fun settingsShellOwnsTheRootScreenshotSurface() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-page-shell").assertIsDisplayed()
        shoot("settings-foundation-root")
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkChat() {
        awaitConversation()
        shoot("11-chat-conversation-dark")
        compose.onNodeWithContentDescription("Menu").performClick()
        shoot("12-drawer-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun composerInputStates() {
        awaitConversation()
        compose.onNodeWithContentDescription("Message").performTextInput("Review the current project")
        shoot("41-composer-ready")
        compose.onNodeWithContentDescription("Message").performTextInput(
            "\nKeep the fix minimal and verify the affected tests.\nThen summarize the exact files changed.",
        )
        compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        shoot("42-composer-multiline")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun interactionSystemComposerFixtures() {
        awaitConversation()
        val state = chatUiState()
        val original = state.value
        try {
            compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Send").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Stop").assertCountEquals(0)
            shoot("composer-empty")
            compose.onNodeWithContentDescription("Message").performTextInput("Review the current project")
            compose.onNodeWithContentDescription("Send").assertIsDisplayed()
            val oneLineAttachmentBottom = assertComposerActionGeometry("Add attachment")
            val oneLineActionBottom = assertComposerActionGeometry("Send")
            assertEquals(oneLineAttachmentBottom, oneLineActionBottom, 0.5f)
            shoot("composer-ready")
            compose.onNodeWithContentDescription("Message").performTextClearance()
            compose.onNodeWithContentDescription("Message").performTextInput(
                "Line one\nLine two\nLine three\nLine four\nLine five\nLine six",
            )
            val multilineAttachmentBottom = assertComposerActionGeometry("Add attachment")
            val multilineActionBottom = assertComposerActionGeometry("Send")
            assertEquals(oneLineAttachmentBottom, multilineAttachmentBottom, 0.5f)
            assertEquals(oneLineActionBottom, multilineActionBottom, 0.5f)
            shoot("composer-multiline")

            val composerKey = "${state.value.currentProjectId.orEmpty()}:${state.value.currentSessionId}"
            state.value = state.value.copy(
                draftPhotos = mapOf(composerKey to listOf(MessagePart.Image("image/png", DECODABLE_PNG))),
            )
            assertEquals(oneLineAttachmentBottom, assertComposerActionGeometry("Add attachment"), 0.5f)
            assertEquals(oneLineActionBottom, assertComposerActionGeometry("Send"), 0.5f)
            assertComposerActionGeometry("Remove photo")
            shoot("composer-attachment")

            state.value = state.value.copy(isRunning = true)
            compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Queue message").assertCountEquals(0)
            assertEquals(oneLineActionBottom, assertComposerActionGeometry("Stop"), 0.5f)
            shoot("composer-running-photo")

            compose.onNodeWithContentDescription("Message").performTextClearance()
            state.value = state.value.copy(draftPhotos = emptyMap())
            compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Queue message").assertCountEquals(0)
            shoot("composer-running")

            compose.onNodeWithContentDescription("Message").performTextInput("Queue the final verification")
            compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
            compose.onNodeWithContentDescription("Queue message").assertIsDisplayed()
            assertEquals(oneLineActionBottom, assertComposerActionGeometry("Stop"), 0.5f)
            assertEquals(oneLineActionBottom, assertComposerActionGeometry("Queue message"), 0.5f)
            shoot("composer-running-queue")

            state.value = state.value.copy(isRunning = false, selected = null, draftPhotos = emptyMap())
            compose.onNodeWithContentDescription("Message").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Add attachment").assertIsNotEnabled()
            compose.onAllNodesWithContentDescription("Send").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Stop").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Queue message").assertCountEquals(0)
            assertEquals(oneLineAttachmentBottom, assertComposerActionGeometry("Add attachment"), 0.5f)
            shoot("composer-disabled")

            state.value = state.value.copy(
                pendingPermission = PermissionRequest(
                    tool = "bash",
                    summary = "Run ./gradlew :app:testDebugUnitTest in PhoneCode",
                ),
            )
            shootDialog("sheet-approval")
        } finally {
            state.value = original
        }
    }

    private companion object {
        const val DECODABLE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL7XQAAAABJRU5ErkJggg=="
    }

    @Test
    fun interactionSystemDrawerAndSettingsFixtures() {
        awaitConversation()
        val state = chatUiState()
        val original = state.value
        try {
            state.value = state.value.copy(projects = emptyList(), sessions = emptyList())
            compose.onNodeWithContentDescription("Menu").performClick()
            shoot("drawer-empty")
            compose.onNodeWithContentDescription("Search chats and projects").performClick()
            compose.onNodeWithContentDescription("Search chats and projects")
                .performTextInput("missing-project")
            shoot("drawer-search")
            compose.onNodeWithContentDescription("Close search").performClick()

            compose.onNodeWithContentDescription("Settings").performClick()
            shootPage("settings-root", "Settings")
            compose.onNodeWithText("Files & permissions").performClick()
            shootPage("settings-toggle", "Files & permissions")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Appearance").performClick()
            shootPage("settings-selection", "Appearance")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Skills").performClick()
            compose.onNodeWithText("Create skill").performClick()
            compose.onNodeWithContentDescription("Skill name").performTextInput("fixture")
            compose.onNodeWithContentDescription("Skill name").assertTextEquals("fixturenew-skill")
            shootPage("settings-editor-dirty", "New skill")
        } finally {
            state.value = original
        }
    }

    @Test
    fun interactionSystemDestructiveMenuFixtures() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        shootScreen("menu-chat-actions", "Delete")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Delete chat?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Delete chat?").assertIsDisplayed()
        shootDialog("dialog-destructive")
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkSettings() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        shootPage("13-settings-root-dark", "Settings")
    }

    @Test
    fun approvalAndSettingsDetails() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
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
        shoot("14-approval-command")
        state.value = state.value.copy(pendingPermission = null)

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        listOf(
            "Files & permissions" to "16-settings-files-permissions",
            "Appearance" to "17-settings-appearance",
            "Personalization" to "18-settings-personalization",
            "Export & import" to "19-settings-export-import",
        ).forEach { (page, image) ->
            compose.onNodeWithText(page).performClick()
            shootPage(image, page)
            compose.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test
    fun consequentialWorkflowStates() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Files & permissions").performClick()
        compose.onNodeWithText("Allow changes automatically").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Enable automatic approval?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Enable automatic approval?").assertIsDisplayed()
        shootDialog("20-approval-policy-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Models & providers").performClick()
        compose.onNodeWithText("Anthropic").performClick()
        shootPage("21-provider-key-explicit-save", "Anthropic")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Agent tools").performClick()
        shootPage("22-agent-tools-inventory", "Agent tools")
        compose.onNodeWithText("Read only").performClick()
        compose.onNodeWithContentDescription("Search tools").performTextInput("missing-production-tool")
        shootPage("22-tools-no-results", "Read-only tools")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Test").performClick()
        shootFullPage("23-mcp-validation", "Add MCP server")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Export & import").performClick()
        compose.onNodeWithText("Import from file").performClick()
        shootDialog("24-import-confirmation")
    }

    @Test
    fun agentInterruptionAndDrawerManagementStates() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingQuestion = QuestionRequest(
                listOf(
                    UserQuestion(
                        question = "Which release channel should receive this build?",
                        header = "Release channel",
                        options = listOf(
                            UserOption("Internal testing", "Fastest review path for the team"),
                            UserOption("Production", "Submit the build for public review"),
                        ),
                    ),
                ),
            ),
        )
        shootDialog("25-agent-question")
        state.value = state.value.copy(pendingQuestion = null)

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Chat options").performClick()
        shootScreen("26-chat-management-menu", "Delete")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Delete chat?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Delete chat?").assertIsDisplayed()
        shootDialog("27-delete-chat-confirmation")
    }

    @Test
    fun providerAndToolManagementStates() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Models & providers").performClick()
        compose.onNodeWithText("Add custom provider").performClick()
        shootDialog("28-custom-provider")
    }

    /**
     * Task 10's evidence pass keeps state setup local and explicit. This is intentionally one
     * navigation sequence: it verifies the UI's real routing and guards against gallery images
     * being fabricated from unasserted state.
     */
    @Test
    fun task10EvidenceStateMatrix() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val state = chatUiState()
        val original = state.value
        val directRoute = mutableStateOf<SettingsRoute>(SettingsRoute.Providers)
        val settingsVm = SettingsViewModel(app)
        val matrixServer = "Matrix MCP"
        val matrixSkill = ManagedSkill(
            id = "global/matrix-release",
            name = "matrix-release",
            manifest = SkillManifest(
                name = "matrix-release",
                description = "Verifies release evidence without publishing anything.",
                body = "## Matrix workflow\n\n1. Inspect the state.\n2. Report the evidence.",
                license = "Apache-2.0",
                compatibility = "Android projects",
            ),
            location = "~/.phonecode/skills/matrix-release/SKILL.md",
            scope = SkillScope.GLOBAL,
            status = SkillStatus.ACTIVE,
        )
        val connectedMatrixSnapshot = McpServerSnapshot(
            connected = true,
            protocolVersion = "2025-06-18",
            serverName = "matrix-mcp",
            serverTitle = matrixServer,
            serverVersion = "1.0.0",
            capabilities = setOf("tools"),
            tools = listOf(
                McpToolDef(
                    "inspect_matrix",
                    "Inspect matrix",
                    "Reports the deterministic matrix fixture.",
                    JsonObject(emptyMap()),
                ),
            ),
        )
        try {
            UiTestSecureKeyStore.replaceWith(emptyMap())
            state.value = original.copy(
                mcpInventoryLoaded = true,
                skillInventoryLoaded = true,
                mcpServers = linkedMapOf(
                    matrixServer to McpServerConfig(url = "https://matrix.example/mcp"),
                ),
                mcpSnapshots = mapOf(matrixServer to connectedMatrixSnapshot),
                mcpToolCount = 1,
                mcpConnecting = emptySet(),
                mcpConfigError = null,
                mcpOperationError = null,
                providerConfigError = null,
                skills = listOf(matrixSkill),
                githubLogin = null,
                githubAuthCode = null,
                githubVerifyUri = null,
            )
            compose.waitForIdle()

            // Render each root destination directly. Navigation behavior is verified by the
            // app-level smoke tests; this visual fixture must not include a stale NavHost layer.
            compose.runOnUiThread {
                compose.activity.setContent {
                    PhoneCodeTheme(darkTheme = false) {
                        key(directRoute.value) {
                            SettingsNavigation(
                                vm = app.chatViewModel,
                                settingsVm = settingsVm,
                                onExit = {},
                                startRoute = directRoute.value,
                                motionEnabled = false,
                            )
                        }
                    }
                }
            }
            fun show(route: SettingsRoute) {
                compose.runOnIdle { directRoute.value = route }
                compose.waitForIdle()
            }

            // Providers: capture a keyless baseline, then rebuild the route after seeding a key
            // so the successful setup state is visibly distinct.
            compose.onNodeWithText("Add custom provider").assertIsDisplayed()
            compose.onAllNodesWithText("Providers").onLast().assertIsDisplayed()
            compose.onAllNodesWithText("Setup required").onFirst().assertIsDisplayed()
            shootFullPage("41-task10-providers-clean", "Providers")

            assertTrue(app.chatViewModel.setKey("openai", "screenshot-openai-key"))
            show(SettingsRoute.Data)
            show(SettingsRoute.Providers)
            compose.onAllNodesWithText("API key saved").onFirst().performScrollTo().assertIsDisplayed()
            shootFullPage("41-task10-provider-key-success", "Providers")

            val providerSaveGate = CompletableDeferred<Unit>()
            compose.runOnUiThread {
                compose.activity.setContent {
                    PhoneCodeTheme(darkTheme = false) {
                        CustomProviderEditor(
                            existingIds = emptySet(),
                            onSave = { _, _ ->
                                providerSaveGate.await()
                                Result.failure(IllegalStateException("Matrix provider save failed"))
                            },
                            onSaved = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Provider name").performTextInput("Matrix Provider")
            compose.onNodeWithText("Enter the provider base URL").assertIsDisplayed()
            shootScreen("42-task10-provider-validation", "Enter the provider base URL")
            compose.onNodeWithContentDescription("Base URL").performTextInput("https://matrix.example/v1")
            compose.onNodeWithContentDescription("Model IDs").performTextInput("matrix-model")
            compose.runOnUiThread { compose.activity.currentFocus?.clearFocus() }
            compose.onNodeWithText("Add custom provider").performScrollTo()
            compose.onNodeWithText("Save").performClick()
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Add custom provider").performScrollTo()
            shootScreen("42-task10-provider-saving", "Add custom provider")
            providerSaveGate.complete(Unit)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Matrix provider save failed").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Matrix provider save failed").assertIsDisplayed()
            compose.onNodeWithText("Add custom provider").performScrollTo()
            shootScreen("42-task10-provider-save-error", "Matrix provider save failed")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("This custom provider has unsaved changes.").assertIsDisplayed()
            shootDialog("42-task10-provider-dirty-discard")
            compose.onNodeWithText("Discard").performClick()

            compose.runOnUiThread {
                compose.activity.setContent {
                    PhoneCodeTheme(darkTheme = false) {
                        key(directRoute.value) {
                            SettingsNavigation(
                                vm = app.chatViewModel,
                                settingsVm = settingsVm,
                                onExit = {},
                                startRoute = directRoute.value,
                                motionEnabled = false,
                            )
                        }
                    }
                }
            }
            show(SettingsRoute.Providers)
            state.value = state.value.copy(providerConfigError = "Providers configuration could not be loaded.")
            compose.waitForIdle()
            compose.onNodeWithText("Providers configuration could not be loaded.").assertIsDisplayed()
            shootFullPage("42-task10-provider-config-error", "Providers")
            state.value = state.value.copy(providerConfigError = null)
            compose.waitForIdle()
            compose.onNodeWithText("OpenAI").performClick()
            compose.onNodeWithText("Remove saved key").performScrollTo().performClick()
            compose.onNodeWithText("Remove saved API key?").assertIsDisplayed()
            shootDialog("43-task10-provider-destructive")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Back").performClick()

            // MCP: explicit success/error and connecting states, validation, guarded discard,
            // and a destructive confirmation for the existing deterministic server.
            state.value = state.value.copy(mcpOperationError = null, mcpConnecting = emptySet())
            compose.waitForIdle()
            show(SettingsRoute.Mcp)
            compose.onNodeWithText(matrixServer).assertIsDisplayed()
            compose.onNodeWithText("Connected · 1 reported tools").assertIsDisplayed()
            shootFullPage("44-task10-mcp-clean-connected", "MCP servers")
            state.value = state.value.copy(mcpOperationError = "Could not reconnect Matrix MCP.")
            compose.waitForIdle()
            compose.onNodeWithText("Could not reconnect Matrix MCP.").assertIsDisplayed()
            shootFullPage("44-task10-mcp-connected-error", "MCP servers")
            state.value = state.value.copy(mcpOperationError = null, mcpConnecting = setOf(matrixServer))
            compose.waitForIdle()
            compose.onNodeWithText("Connecting").assertIsDisplayed()
            shootFullPage("44-task10-mcp-connecting", "MCP servers")
            state.value = state.value.copy(mcpConnecting = emptySet())
            compose.waitForIdle()

            compose.onNodeWithText("Add server").performScrollTo().performClick()
            compose.onNodeWithText("Test").performScrollTo().performClick()
            compose.onNodeWithText("Name is required").assertIsDisplayed()
            shootFullPage("23-mcp-validation", "Add MCP server")
            compose.onNodeWithContentDescription("Server name").performTextInput("matrix-draft")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("This server has unsaved changes.").assertIsDisplayed()
            shootDialog("45-task10-mcp-dirty-discard")
            compose.onNodeWithText("Discard").performClick()

            compose.onNodeWithText(matrixServer).performClick()
            compose.onNodeWithText("Delete server").performScrollTo().performClick()
            compose.onNodeWithText("Delete MCP server?").assertIsDisplayed()
            shootDialog("45-task10-mcp-destructive")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Back").performClick()

            // Skills: destination loading, active detail, delete-operation feedback, then a
            // separate unsaved editor that exercises the route-level Back guard.
            state.value = state.value.copy(skillInventoryLoaded = false)
            compose.waitForIdle()
            show(SettingsRoute.Skill(matrixSkill.id))
            compose.onNodeWithText("Loading skills…").assertIsDisplayed()
            shootFullPage("35-skill-inventory-loading", "Skill")
            state.value = state.value.copy(skillInventoryLoaded = true)
            compose.waitForIdle()
            compose.onNodeWithText("Verifies release evidence without publishing anything.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Edit skill").assertIsDisplayed()
            shootFullPage("35-skill-active-detail", "matrix-release")
            compose.onNodeWithText("Delete skill").performScrollTo().performClick()
            compose.onNodeWithText("Delete skill?").assertIsDisplayed()
            state.value = state.value.copy(
                settingsOperations = state.value.settingsOperations +
                    (skillDeleteOperationKey(matrixSkill.id) to SettingsOperation(running = true)),
            )
            compose.waitForIdle()
            compose.onNodeWithText("Deleting…").assertIsDisplayed()
            shootDialog("46-task10-skill-delete-running")
            state.value = state.value.copy(
                settingsOperations = state.value.settingsOperations +
                    (skillDeleteOperationKey(matrixSkill.id) to SettingsOperation(error = "Matrix delete denied")),
            )
            compose.waitForIdle()
            compose.onNodeWithText("Could not delete matrix-release: Matrix delete denied").assertIsDisplayed()
            shootDialog("46-task10-skill-operation-error")
            compose.onNodeWithText("Cancel").performClick()
            show(SettingsRoute.Skills)
            show(SettingsRoute.NewSkill)
            compose.onNodeWithContentDescription("Skill name").performTextInput("matrix-draft")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("This skill has unsaved changes.").assertIsDisplayed()
            shootDialog("47-task10-skill-dirty-discard")
            compose.onNodeWithText("Discard").performClick()

            show(SettingsRoute.Skills)
            show(SettingsRoute.NewSkill)
            compose.onNodeWithText("Advanced source").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Skill source").performTextClearance()
            compose.onNodeWithText("Advanced source").performScrollTo().performClick()
            compose.onNodeWithText("Fix the SKILL.md source before returning to the guided editor.").assertIsDisplayed()
            shootFullPage("47-task10-skill-validation-error", "New skill")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()

            // Git: signed-out, device authorization, connected, a dirty manual-credential
            // draft, and guarded sign-out without mutating the real credential store.
            show(SettingsRoute.Git)
            compose.onNodeWithText("Sign in with GitHub").assertIsDisplayed()
            shootFullPage("48-task10-git-clean", "Git")
            state.value = state.value.copy(
                githubAuthCode = "MATRIX-9QW2",
                githubVerifyUri = "https://github.com/login/device",
                githubLogin = null,
            )
            compose.waitForIdle()
            compose.onNodeWithText("Enter this code on GitHub").assertIsDisplayed()
            compose.onNodeWithText("MATRIX-9QW2").assertIsDisplayed()
            shootFullPage("48-task10-git-device-code", "Git")
            compose.runOnUiThread {
                compose.activity.setContent {
                    PhoneCodeTheme(darkTheme = false) {
                        GitPage(
                            vm = app.chatViewModel,
                            settingsVm = settingsVm,
                            onBack = {},
                            openUrl = { _, _ -> "No browser can open the GitHub authorization page." },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Open github.com/login/device").performClick()
            compose.onNodeWithText("No browser can open the GitHub authorization page.").assertIsDisplayed()
            shootFullPage("48-task10-git-browser-error", "Git")
            compose.runOnUiThread {
                compose.activity.setContent {
                    PhoneCodeTheme(darkTheme = false) {
                        key(directRoute.value) {
                            SettingsNavigation(
                                vm = app.chatViewModel,
                                settingsVm = settingsVm,
                                onExit = {},
                                startRoute = directRoute.value,
                                motionEnabled = false,
                            )
                        }
                    }
                }
            }
            show(SettingsRoute.Git)
            state.value = state.value.copy(githubAuthCode = null, githubVerifyUri = null, githubLogin = "matrix-user")
            compose.waitForIdle()
            compose.onNodeWithText("@matrix-user").assertIsDisplayed()
            compose.onNodeWithText("GitHub account connected").assertIsDisplayed()
            shootFullPage("49-task10-git-connected", "Git")
            compose.onNodeWithText("Sign out").performClick()
            compose.onNodeWithText("Sign out of GitHub?").assertIsDisplayed()
            shootDialog("50-task10-git-destructive")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Advanced Git settings").performClick()
            compose.onNodeWithContentDescription("Git username").performTextInput("matrix-user")
            compose.onNodeWithContentDescription("Manual Git access token").performTextInput("matrix-token")
            UiTestSecureKeyStore.failNextWrite()
            compose.onNodeWithText("Save manual credentials").performScrollTo().performClick()
            compose.onNodeWithText("Manual Git credentials could not be saved securely.").assertIsDisplayed()
            shootFullPage("50-task10-git-save-error", "Git")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("These Git settings have unsaved changes.").assertIsDisplayed()
            shootDialog("50-task10-git-dirty-discard")
            compose.onNodeWithText("Discard").performClick()
        } finally {
            state.value = original
            UiTestSecureKeyStore.replaceWith(
                mapOf("anthropic" to "screenshot-fixture-key", "openai" to "screenshot-openai-key"),
            )
        }
    }

    @Test
    fun extensionManagementGalleryShowsConnectedMcpAndSkillStates() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        val original = state.value
        state.value = original.copy(
            mcpServers = linkedMapOf(
                "Workspace Index" to McpServerConfig(
                    url = "https://workspace-index.example/mcp",
                    headers = mapOf("Authorization" to "Bearer ••••••••"),
                ),
                "Issue Tracker" to McpServerConfig(url = "https://issues.example/mcp"),
                "Deploy Preview" to McpServerConfig(url = "https://preview.example/mcp"),
                "Local Browser" to McpServerConfig(
                    url = "http://localhost:4318/mcp",
                    enabled = false,
                ),
            ),
            mcpSnapshots = mapOf(
                "Workspace Index" to McpServerSnapshot(
                    connected = true,
                    protocolVersion = "2025-06-18",
                    serverName = "workspace-index",
                    serverTitle = "Workspace Index",
                    serverVersion = "2.4.0",
                    capabilities = setOf("tools", "resources"),
                    tools = listOf(
                        McpToolDef("search_workspace", "Search workspace", "Find symbols and files across the current project.", JsonObject(emptyMap())),
                        McpToolDef("read_source", "Read source", "Open a source file with stable line references.", JsonObject(emptyMap())),
                        McpToolDef("dependency_graph", "Dependency graph", "Trace dependencies between project modules.", JsonObject(emptyMap())),
                    ),
                    instructions = "Use this server for project-wide source discovery.",
                ),
                "Issue Tracker" to McpServerSnapshot(
                    connected = false,
                    error = "Authentication required",
                ),
            ),
            mcpConnecting = setOf("Deploy Preview"),
            mcpToolCount = 3,
            skills = listOf(
                ManagedSkill(
                    id = "global/release-pilot",
                    name = "release-pilot",
                    manifest = SkillManifest(
                        name = "release-pilot",
                        description = "Runs a careful release-readiness pass before publishing.",
                        body = "## Workflow\n\n1. Verify the release build.\n2. Review store metadata.\n3. Report blockers before publishing.",
                        license = "Apache-2.0",
                        compatibility = "Android projects",
                    ),
                    location = "~/.phonecode/skills/release-pilot/SKILL.md",
                    scope = SkillScope.GLOBAL,
                    status = SkillStatus.ACTIVE,
                ),
                ManagedSkill(
                    id = "project/legacy-deploy",
                    name = "legacy-deploy",
                    manifest = null,
                    location = ".phonecode/skills/legacy-deploy/SKILL.md",
                    scope = SkillScope.PROJECT,
                    status = SkillStatus.INVALID,
                    issue = "Invalid SKILL.md frontmatter",
                ),
                ManagedSkill(
                    id = "project/code-review",
                    name = "code-review",
                    manifest = SkillManifest(
                        name = "code-review",
                        description = "Reviews a change for correctness and maintainability.",
                        body = "Review the active change and report actionable findings.",
                    ),
                    location = ".phonecode/skills/code-review/SKILL.md",
                    scope = SkillScope.PROJECT,
                    status = SkillStatus.SHADOWED,
                ),
            ),
        )
        try {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("MCP servers").performClick()

            compose.onNodeWithText("Workspace Index").assertIsDisplayed()
            compose.onNodeWithText("Connected · 3 reported tools").assertIsDisplayed()
            compose.onNodeWithText("Needs attention · Authentication required").assertIsDisplayed()
            compose.onNodeWithText("Connecting").assertIsDisplayed()
            compose.onNodeWithText("Off · Test to enable").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootPage("31-mcp-server-states", "MCP servers")

            compose.onNodeWithText("Workspace Index").performClick()
            compose.onNodeWithText("Connected to Workspace Index").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootPage("32-mcp-connected-editor", "Workspace Index")
            compose.onNodeWithText("Trace dependencies between project modules.").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            shootPage("33-mcp-connected-tools", "Workspace Index")

            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithText("Skills").performClick()
            compose.onNodeWithText("release-pilot").assertIsDisplayed()
            compose.onNodeWithText("legacy-deploy").assertIsDisplayed()
            compose.onNodeWithText("code-review").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootPage("34-skills-mixed-states", "Skills")

            compose.onNodeWithText("release-pilot").performClick()
            compose.onNodeWithText("Runs a careful release-readiness pass before publishing.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Edit skill").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootFullPage("35-skill-active-detail", "release-pilot")

            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithText("Create skill").performClick()
            compose.onNodeWithContentDescription("When to use this skill").assertIsDisplayed()
            compose.onNodeWithContentDescription("Skill instructions").assertIsDisplayed()
            shootPage("40-skill-guided-editor", "New skill")
        } finally {
            state.value = original
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-xhdpi")
    fun compactQuestionDialogKeepsActionsReachable() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingQuestion = QuestionRequest(
                listOf(
                    UserQuestion(
                        question = "Which release channel should receive this build?",
                        header = "Release channel",
                        options = listOf(
                            UserOption("Internal testing", "Fastest review path for the team"),
                            UserOption("Production", "Submit the build for public review"),
                        ),
                    ),
                ),
            ),
        )
        shootDialog("30-agent-question-compact")
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun expandedSettingsLayout() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        shootPage("29-settings-expanded", "Settings")
    }

    @Test
    fun longApprovalDetailsStayReviewable() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "apply_patch",
                summary = "Update app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt. ".repeat(50),
            ),
        )

        compose.onNodeWithText("Section 1 of 2").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        shoot("36-approval-long-details")
        compose.onNodeWithText("Next section").performScrollTo().performClick()
        compose.onNodeWithText("Section 2 of 2").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        shoot("37-approval-long-details-next")
        state.value = state.value.copy(pendingPermission = null)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun liveTurnRecoveryStates() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        val original = state.value
        try {
            state.value = original.copy(
                lines = original.lines + ChatLine.ToolActivity(
                    id = "release-check",
                    name = "bash",
                    status = ToolStatus.RUNNING,
                    detail = "./gradlew verifyPlayRelease",
                ),
                isRunning = true,
                error = null,
                turnOutcome = null,
                queued = listOf(
                    "Also verify the store listing assets",
                    "Keep the release notes concise",
                    "Check the final bundle manifest",
                ),
            )
            compose.waitForIdle()
            shootScreen("38-chat-running-queue", "Settings dark mode")

            state.value = state.value.copy(
                isRunning = false,
                error = "The connection ended before the turn completed.",
                turnOutcome = TurnOutcome.FAILED,
            )
            check(state.value.error == "The connection ended before the turn completed.")
            shoot("39-chat-failed-recovery")
        } finally {
            state.value = original
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxxhdpi")
    fun playListingPhoneScreenshots() {
        awaitConversation()
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            lines = listOf(
                ChatLine.User("Add dark mode to Settings and keep the system default."),
                ChatLine.Assistant(
                    "Done. Settings now supports System, Light, and Dark. System follows the " +
                        "phone theme automatically, and your choice is preserved between launches.",
                ),
            ),
        )
        settleAnimation(1_000)
        compose.onNodeWithText("Add dark mode to Settings and keep the system default.").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            "../play/0.5.1/graphics/phone/01-agent-conversation.png",
        )

        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "bash",
                summary = "Run ./gradlew testDebugUnitTest for the dark-mode settings change",
            ),
        )
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Approve agent action?").assertIsDisplayed()
        captureScreenRoboImage("../play/0.5.1/graphics/phone/02-action-approval.png")
        state.value = state.value.copy(pendingPermission = null)

        compose.onNodeWithContentDescription("Menu").performClick()
        settleAnimation()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            "../play/0.5.1/graphics/phone/03-project-drawer.png",
        )

        compose.onNodeWithContentDescription("Settings").performClick()
        settleAnimation()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            "../play/0.5.1/graphics/phone/04-settings.png",
        )
    }
}
