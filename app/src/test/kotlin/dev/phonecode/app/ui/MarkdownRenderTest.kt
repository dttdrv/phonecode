package dev.phonecode.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phonecode.app.ui.chat.MarkdownBlocks
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Renders the new markdown features (table, strikethrough, task list) to a PNG for visual review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MarkdownRenderTest {

    @get:Rule val compose = createComposeRule()

    @Test fun rendersTableStrikethroughAndTasks() {
        val md = """
            Here is a comparison and a checklist.

            | Feature | Status | Effort |
            | :-- | :--: | --: |
            | Tables | done | low |
            | ~~Manual~~ **auto** layout | wip | high |
            | Task lists | done | low |

            - [x] parse GFM tables
            - [ ] render graphs
            - plain bullet with `code` and a [link](https://example.com)
        """.trimIndent()
        compose.setContent {
            PhoneCodeTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(16.dp)) { MarkdownBlocks(md) }
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/markdown-features.png")
    }
}
