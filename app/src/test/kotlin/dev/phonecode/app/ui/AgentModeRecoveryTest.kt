package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.agent.AgentMode
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-xxxhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class AgentModeRecoveryTest {

    private val sessionId = "interrupted-build-session"

    private val seedSettings = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "mode-test-key"))
            java.io.File(app.filesDir, "app_settings.json")
                .writeText("""{"onboarded":true,"defaultMode":"BUILD","activeSessionId":"$sessionId"}""")
            SessionStore(java.io.File(app.filesDir, "sessions")).save(
                PersistedSession(
                    id = sessionId,
                    title = "Interrupted Build chat",
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList(),
                    activeTurn = true,
                    agentMode = AgentMode.BUILD.name,
                ),
            )
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.RuleChain =
        org.junit.rules.RuleChain.outerRule(seedSettings).around(compose)

    @Test
    fun interruptedBuildSessionFailsClosedToPlanMode() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel

        compose.waitUntil(5_000) { !vm.state.value.sessionLoading }

        assertEquals(sessionId, vm.state.value.currentSessionId)
        assertEquals(AgentMode.PLAN, vm.state.value.agentMode)
        assertEquals(
            AgentMode.PLAN.name,
            SessionStore(java.io.File(app.filesDir, "sessions")).load(sessionId)?.agentMode,
        )
    }
}
