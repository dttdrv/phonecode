package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.QuestionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.agent.TurnOutcome
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.ManagedSkill
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.ProjectStore
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.SkillStatus
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
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

    /**
     * Seeds a realistic conversation BEFORE the activity launches, so launch-restore renders it.
     * Deliberately NO cleanup and NO wiping: deleting the seed between tests breaks later tests'
     * launch-restore in ways that only reproduce inside Robolectric's shared-worker filesystem
     * (verified empirically); leftover files are harmless - UiSmokeTest disambiguates instead.
     */
    private val seedSession = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "screenshot-fixture-key"))
            // First-run onboarding would otherwise cover the app for every test.
            File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
            ProjectStore(File(filesDir, "projects.json")).replace(
                listOf(Project("project-screenshot", "PhoneCode", "folder-screenshot")),
            )
            SessionStore(File(filesDir, "sessions")).save(
                PersistedSession(
                    id = "session-screenshot",
                    title = "Dark mode for settings",
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
                compose.onAllNodesWithText("Dark mode for settings").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            compose.onRoot().captureRoboImage("screenshots/debug-drawer-failure.png")
            throw e
        }
        compose.onNodeWithText("Dark mode for settings").performClick()
        try {
            compose.waitUntil(15_000) { conversationVisible() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Capture what's actually on screen so the failure is diagnosable from the PNG.
            compose.onRoot().captureRoboImage("screenshots/debug-await-failure.png")
            throw e
        }
    }

    private fun shoot(name: String) {
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun shootScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/$name.png")
    }

    /** Dialog windows can keep Espresso's global-idle probe busy even after Compose has settled. */
    private fun shootDialog(name: String) {
        compose.mainClock.advanceTimeBy(500)
        captureScreenRoboImage("screenshots/$name.png")
    }

    @Test
    fun chatScreens() {
        awaitConversation()
        shoot("01-chat-conversation")

        compose.onNodeWithContentDescription("Switch model").performClick()
        shootScreen("03-model-picker")
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
        shoot("08-settings-root")
        compose.onNodeWithText("Providers").performClick()
        shoot("09-settings-providers")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Git").performClick()
        shoot("10-settings-git")
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
    @Config(qualifiers = "+night")
    fun darkSettings() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        shoot("13-settings-root-dark")
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
        shootScreen("14-approval-command")
        state.value = state.value.copy(pendingPermission = null)

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        listOf(
            "General" to "15-settings-general",
            "Files & permissions" to "16-settings-files-permissions",
            "Appearance" to "17-settings-appearance",
            "Personalization" to "18-settings-personalization",
            "Export & import" to "19-settings-export-import",
        ).forEach { (page, image) ->
            compose.onNodeWithText(page).performClick()
            shoot(image)
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
        shootScreen("20-approval-policy-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("Anthropic").performClick()
        shootScreen("21-provider-key-explicit-save")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Agent tools").performClick()
        compose.onNodeWithContentDescription("Search tools").performTextInput("missing-production-tool")
        shootScreen("22-tools-no-results")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Save").performClick()
        shootScreen("23-mcp-validation")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Export & import").performClick()
        compose.onNodeWithText("Import from a file").performClick()
        shootScreen("24-import-confirmation")
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
        shootScreen("26-chat-management-menu")
        compose.onNodeWithText("Delete").performClick()
        shootScreen("27-delete-chat-confirmation")
    }

    @Test
    fun providerAndToolManagementStates() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("Add custom provider").performClick()
        shootDialog("28-custom-provider")
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
            compose.onNodeWithText("Connected · 3 tools").assertIsDisplayed()
            compose.onNodeWithText("Failed · Authentication required").assertIsDisplayed()
            compose.onNodeWithText("Connecting").assertIsDisplayed()
            compose.onNodeWithText("Off").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shoot("31-mcp-server-states")

            compose.onNodeWithText("Workspace Index").performClick()
            compose.onNodeWithText("Connected to Workspace Index").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootScreen("32-mcp-connected-editor")
            compose.onNodeWithText("Trace dependencies between project modules.").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            shoot("33-mcp-connected-tools")

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
            shootScreen("34-skills-mixed-states")

            compose.onNodeWithText("release-pilot").performClick()
            compose.onNodeWithText("Runs a careful release-readiness pass before publishing.").assertIsDisplayed()
            compose.onNodeWithText("Edit skill").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            shootScreen("35-skill-active-detail")
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
        shoot("29-settings-expanded")
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
        shootDialog("36-approval-long-details")
        compose.onNodeWithText("Next section").performScrollTo().performClick()
        compose.onNodeWithText("Section 2 of 2").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        shootDialog("37-approval-long-details-next")
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
            shootScreen("38-chat-running-queue")

            state.value = state.value.copy(
                isRunning = false,
                error = "The connection ended before the turn completed.",
                turnOutcome = TurnOutcome.FAILED,
            )
            compose.waitForIdle()
            shootScreen("39-chat-failed-recovery")
        } finally {
            state.value = original
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxxhdpi")
    fun playListingPhoneScreenshots() {
        awaitConversation()
        compose.onNode(
            hasScrollAction() and hasAnyDescendant(
                hasText("Does it follow the system setting by default?"),
            ),
        ).performScrollToIndex(0)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/01-agent-conversation.png",
        )

        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val stateField = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(
            pendingPermission = PermissionRequest(
                tool = "bash",
                summary = "Run ./gradlew testDebugUnitTest for the dark-mode settings change",
            ),
        )
        compose.waitForIdle()
        captureScreenRoboImage("../play/0.5.0/graphics/phone/02-action-approval.png")
        state.value = state.value.copy(pendingPermission = null)

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/03-project-drawer.png",
        )

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onRoot().captureRoboImage(
            "../play/0.5.0/graphics/phone/04-settings.png",
        )
    }
}
