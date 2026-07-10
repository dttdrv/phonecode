package dev.phonecode.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.ui.chat.TodoPanel
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoPriority
import dev.phonecode.tools.todo.TodoStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the compact, collapsible TodoPanel (collapsed summary, then expanded) for visual review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class TodoPanelRenderTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(debugManifestOnlyRule()).around(compose)

    private val todos = listOf(
        TodoItem("1", "Read the existing provider layer", TodoStatus.COMPLETED, TodoPriority.MEDIUM),
        TodoItem("2", "Wire the streaming response into the chat view", TodoStatus.IN_PROGRESS, TodoPriority.HIGH),
        TodoItem("3", "Add per-model token limits", TodoStatus.PENDING, TodoPriority.MEDIUM),
        TodoItem("4", "Write the compaction tests", TodoStatus.PENDING, TodoPriority.LOW),
    )

    @Test fun rendersCollapsedThenExpanded() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(8.dp)) { TodoPanel(todos) }
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/todo-panel-collapsed.png")
        compose.onNodeWithText("Tasks 1/4").performClick()
        compose.onRoot().captureRoboImage("screenshots/todo-panel-expanded.png")
    }
}
