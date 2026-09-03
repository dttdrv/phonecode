package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.data.Project
import dev.phonecode.app.data.SessionMeta
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-xxxhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class DrawerNavigationPolishTest {

    private val seedSettings = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<PhoneCodeApplication>()
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "drawer-test-key"))
            java.io.File(app.filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.RuleChain =
        org.junit.rules.RuleChain.outerRule(seedSettings).around(compose)

    private val project = Project(
        id = "project-alpha",
        name = "PhoneCode Android application",
    )
    private val otherProject = Project(
        id = "project-beta",
        name = "Release workspace",
    )
    private val activeChat = SessionMeta(
        id = "chat-active",
        title = "Active implementation",
        updatedAt = System.currentTimeMillis(),
        projectId = project.id,
    )
    private val archivedChat = SessionMeta(
        id = "chat-archived",
        title = "Archived migration notes",
        updatedAt = System.currentTimeMillis() - 1_000,
        projectId = project.id,
        archived = true,
    )

    private fun stateFlow(): MutableStateFlow<ChatUiState> {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val field = app.chatViewModel.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        return field.get(app.chatViewModel) as MutableStateFlow<ChatUiState>
    }

    private fun showFixture(running: Boolean = false) {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Projects").assertIsDisplayed()
        val state = stateFlow()
        state.value = state.value.copy(
            projects = listOf(project, otherProject),
            sessions = listOf(activeChat, archivedChat),
            currentSessionId = activeChat.id,
            currentProjectId = project.id,
            isRunning = running,
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(activeChat.title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun searchFor(query: String) {
        compose.onNodeWithContentDescription("Search chats and projects").performClick()
        compose.onNode(
            hasContentDescription("Search chats and projects") and hasSetTextAction(),
        ).performTextReplacement(query)
        compose.waitForIdle()
    }

    @Test
    fun searchTemporarilyRevealsMatchesInsideCollapsedProjects() {
        showFixture()
        compose.onNodeWithText(project.name).performClick()
        compose.waitForIdle()
        check(compose.onAllNodesWithText(activeChat.title).fetchSemanticsNodes().isEmpty())

        searchFor("implementation")
        compose.onNodeWithText(activeChat.title).assertIsDisplayed()

        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitForIdle()
        check(compose.onAllNodesWithText(activeChat.title).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun searchTemporarilyRevealsArchivedMatches() {
        showFixture()
        check(compose.onAllNodesWithText(archivedChat.title).fetchSemanticsNodes().isEmpty())

        searchFor("Archived migration")
        compose.onNodeWithText(archivedChat.title).assertIsDisplayed()
        assertEquals(
            "Expanded",
            compose.onNodeWithText("Archived").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        )

        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitForIdle()
        check(compose.onAllNodesWithText(archivedChat.title).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun projectNewChatLivesInOverflowSoTheProjectNameKeepsTheRowWidth() {
        showFixture()
        check(
            compose.onAllNodesWithContentDescription("New chat in ${project.name}")
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onAllNodesWithContentDescription("Project options")[0].performClick()
        compose.onNodeWithText("New chat").assertIsDisplayed()
    }

    @Test
    fun renameSaveRequiresAChangedNonBlankName() {
        assertFalse(renameSaveEnabled(project.name, project.name))
        assertFalse(renameSaveEnabled(project.name, "  ${project.name}  "))
        assertFalse(renameSaveEnabled(project.name, "   "))
        assertTrue(renameSaveEnabled(project.name, "PhoneCode release app"))
    }

    @Test
    fun runningChatHidesLifecycleMutationsThatCannotSucceed() {
        showFixture(running = true)
        compose.onNodeWithContentDescription("Chat options").performClick()

        check(compose.onAllNodesWithText("Move to…").fetchSemanticsNodes().isEmpty())
        check(compose.onAllNodesWithText("Archive").fetchSemanticsNodes().isEmpty())
        check(compose.onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Stop the agent to move, archive, or delete this chat.")
            .assertIsDisplayed()
    }

    @Test
    fun moveMenuMarksAndDisablesTheCurrentProject() {
        showFixture()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Move to…").performClick()

        compose.onNodeWithText("${project.name} (current)")
            .assertIsDisplayed()
            .assertIsSelected()
            .assertIsNotEnabled()
    }

    @Test
    fun deleteChatConfirmationStatesPermanenceAndNoUndo() {
        showFixture()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText(
            "${activeChat.title} will be permanently removed from this device. This cannot be undone.",
        ).assertIsDisplayed()
    }

    @Test
    fun chatDateRemainsInsideTheNavigationTarget() {
        showFixture()
        val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(activeChat.updatedAt))

        compose.onNodeWithText(date).assertHasClickAction()
    }

    @Test
    fun searchKeepsScrollChromeAndPredictiveBackStaysLatchedThroughCommit() {
        val drawer = java.io.File("src/main/kotlin/dev/phonecode/app/ui/drawer/WorkspaceDrawer.kt").readText()
        val shell = java.io.File("src/main/kotlin/dev/phonecode/app/ui/PhoneCodeApp.kt").readText()

        assertFalse(drawer.contains("(listScrolled || hasMoreBelow) && !searchExpanded"))
        assertTrue(shell.contains("drawerVisible || drawerBackGestureInProgress"))
        assertTrue(shell.contains("drawerBackGestureInProgress = true"))
        assertTrue(shell.contains("finally {\n                drawerBackGestureInProgress = false"))
    }
}
