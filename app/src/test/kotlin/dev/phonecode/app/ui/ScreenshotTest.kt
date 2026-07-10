package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.SessionStore
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

/**
 * The design feedback loop: renders the REAL app (same composition as UiSmokeTest) to PNGs in
 * app/screenshots/ via Roborazzi, so UI changes can be SEEN and judged without a device.
 * Runs as part of the normal unit-test task; screenshots are overwritten on every run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
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
            // First-run onboarding would otherwise cover the app for every test.
            File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
            SessionStore(File(filesDir, "sessions")).save(
                PersistedSession(
                    id = "session-screenshot",
                    title = "Dark mode for settings",
                    updatedAt = System.currentTimeMillis() + 3_600_000,
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
        compose.onNodeWithText("Skip for now").performClick()
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

    private fun dismissToolsMenu() {
        compose.onNodeWithText("Upload").performClick()
        compose.waitForIdle()
    }

    @Test
    fun chatScreens() {
        awaitConversation()
        shoot("01-chat-conversation")

        compose.onNodeWithContentDescription("Tools").performClick()
        shootScreen("02-tools-menu")
        dismissToolsMenu()

        // Model sheet from the composer pill; re-selecting the current model closes it.
        compose.onNodeWithText("Claude Opus 4.8").performClick()
        shootScreen("03-model-picker")
        compose.onAllNodesWithText("Claude Opus 4.8").onLast().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("New chat").performClick()
        shoot("06-chat-empty")
    }

    @Test
    fun drawerAndSettings() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        shoot("07-drawer")

        compose.onNodeWithText("Settings").performClick()
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
        compose.onNodeWithContentDescription("Tools").performClick()
        shootScreen("14-tools-menu-dark")
        dismissToolsMenu()
        compose.onNodeWithContentDescription("Menu").performClick()
        shoot("12-drawer-dark")
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkSettings() {
        awaitConversation()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        shoot("13-settings-root-dark")
    }
}
