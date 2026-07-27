package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.agent.AgentMode
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-xxxhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class AgentModePersistenceTest {

    private val sessionId = "mode-session"
    private val startupReadStarted = CountDownLatch(1)
    private val releaseStartupRead = CountDownLatch(1)

    private val seedSettings = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<PhoneCodeApplication>()
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "mode-test-key"))
            UiTestSecureKeyStore.blockNextRead(
                name = "github.login",
                started = startupReadStarted,
                release = releaseStartupRead,
            )
            java.io.File(app.filesDir, "app_settings.json")
                .writeText("""{"onboarded":true,"defaultMode":"BUILD","activeSessionId":"$sessionId"}""")
            SessionStore(java.io.File(app.filesDir, "sessions")).save(
                PersistedSession(
                    id = sessionId,
                    title = "Plan chat",
                    updatedAt = System.currentTimeMillis(),
                    messages = listOf(
                        PersistedMessage(
                            PersistedRole.USER,
                            listOf(PersistedPart.Text("Keep this Plan chat")),
                        ),
                    ),
                    agentMode = AgentMode.PLAN.name,
                ),
            )
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.RuleChain =
        org.junit.rules.RuleChain.outerRule(seedSettings).around(compose)

    @Test
    fun planModeIsRestoredAndEveryModeChangeIsCheckpointed() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val store = SessionStore(java.io.File(app.filesDir, "sessions"))

        check(startupReadStarted.await(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { !vm.state.value.sessionLoading }
        assertEquals(sessionId, vm.state.value.currentSessionId)
        assertEquals(AgentMode.PLAN, vm.state.value.agentMode)
        releaseStartupRead.countDown()
        compose.waitForIdle()
        assertEquals(
            "A slower global startup task must not overwrite the restored per-chat authority",
            AgentMode.PLAN,
            vm.state.value.agentMode,
        )

        vm.setAgentMode(AgentMode.BUILD)
        compose.waitUntil(5_000) {
            vm.state.value.agentMode == AgentMode.BUILD &&
                store.load(sessionId)?.agentMode == AgentMode.BUILD.name
        }
        assertEquals(AgentMode.BUILD, vm.state.value.agentMode)
        assertEquals(AgentMode.BUILD.name, store.load(sessionId)?.agentMode)

        vm.setAgentMode(AgentMode.PLAN)
        compose.waitUntil(5_000) {
            store.load(sessionId)?.agentMode == AgentMode.PLAN.name
        }
        assertEquals(AgentMode.PLAN, vm.state.value.agentMode)
        assertEquals(AgentMode.PLAN.name, store.load(sessionId)?.agentMode)
    }
}
