package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class SessionRecoveryUiTest {

    private val seedRecovery = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "session-recovery-key"))
            File(context.filesDir, "app_settings.json").writeText(
                """{"onboarded":true,"activeSessionId":"session-recovery"}""",
            )
            SessionStore(File(context.filesDir, "sessions")).save(
                PersistedSession(
                    id = "session-recovery",
                    title = "Failed turn",
                    updatedAt = System.currentTimeMillis() + 100_000,
                    messages = listOf(
                        PersistedMessage(
                            PersistedRole.USER,
                            listOf(PersistedPart.Text("Failed prompt")),
                        ),
                    ),
                    activeTurn = true,
                    turnOutcome = "FAILED",
                    queuedMessages = listOf("Recovered after relaunch"),
                ),
            )
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedRecovery).around(compose)

    @Test
    fun relaunchRestoresFollowUpsBeforeOfferingRetry() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        compose.waitUntil(5_000) {
            app.chatViewModel.state.value.currentSessionId == "session-recovery" &&
                !app.chatViewModel.state.value.sessionLoading
        }

        assertEquals(listOf("Recovered after relaunch"), app.chatViewModel.state.value.queued)
        compose.onNodeWithText("1 unsent follow-up").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
