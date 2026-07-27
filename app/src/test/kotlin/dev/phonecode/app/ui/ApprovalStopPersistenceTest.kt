package dev.phonecode.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.agent.PermissionRequest
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import dev.phonecode.app.data.SessionStore
import dev.phonecode.app.data.toDomain
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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
class ApprovalStopPersistenceTest {

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider
                .getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(mapOf("anthropic" to "approval-stop-key"))
            File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSettings).around(compose)

    @Test
    fun explicitStopWhileAwaitingApprovalRestoresAsStopped() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        compose.waitUntil(5_000) { !vm.state.value.sessionLoading }
        val state = vm.mutableState()
        val history = listOf(
            ChatMessage(Role.USER, listOf(MessagePart.Text("Update the release notes"))),
            ChatMessage(
                Role.ASSISTANT,
                listOf(MessagePart.ToolCall("approval-stop", "write", """{"path":"RELEASE.md"}""")),
            ),
        )
        vm.historyField().set(vm, history)
        state.value = state.value.copy(
            isRunning = true,
            lines = listOf(
                ChatLine.User("Update the release notes"),
                ChatLine.ToolActivity(
                    id = "approval-stop",
                    name = "write",
                    status = ToolStatus.AWAITING_APPROVAL,
                    detail = "Write RELEASE.md",
                ),
            ),
            pendingPermission = PermissionRequest("write", "Write RELEASE.md"),
        )
        val stoppedSessionId = state.value.currentSessionId
        val store = vm.sessionStore()

        vm.cancel()

        compose.waitUntil(5_000) {
            store.load(stoppedSessionId)?.messages
                ?.flatMap(PersistedMessage::parts)
                ?.filterIsInstance<PersistedPart.ToolResult>()
                ?.any { it.callId == "approval-stop" } == true
        }
        val restored = vm.restoreLines(
            store.load(stoppedSessionId)!!.messages.map(PersistedMessage::toDomain),
        )
            .filterIsInstance<ChatLine.ToolActivity>()
            .single { it.id == "approval-stop" }
        assertEquals(ToolStatus.STOPPED, restored.status)
        assertEquals("Stopped before approval.", restored.detail)
    }

    @Test
    fun genericPersistedToolErrorRestoresAsError() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        compose.waitUntil(5_000) { !vm.state.value.sessionLoading }
        val state = vm.mutableState()
        val sessionId = "generic-tool-error"
        val session = PersistedSession(
            id = sessionId,
            title = "Failed write",
            updatedAt = System.currentTimeMillis() + 1_000,
            messages = listOf(
                PersistedMessage(
                    PersistedRole.ASSISTANT,
                    listOf(PersistedPart.ToolCall("failed-write", "write", """{"path":"README.md"}""")),
                ),
                PersistedMessage(
                    PersistedRole.USER,
                    listOf(
                        PersistedPart.ToolResult(
                            "failed-write",
                            "Permission denied",
                            isError = true,
                        ),
                    ),
                ),
            ),
        )

        val restored = vm.restoreLines(session.messages.map(PersistedMessage::toDomain))
            .filterIsInstance<ChatLine.ToolActivity>()
            .single { it.id == "failed-write" }
        assertEquals(ToolStatus.ERROR, restored.status)
        assertEquals("Permission denied", restored.detail)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dev.phonecode.app.agent.ChatViewModel.mutableState(): MutableStateFlow<ChatUiState> {
        val field = javaClass.getDeclaredField("_state").apply { isAccessible = true }
        return field.get(this) as MutableStateFlow<ChatUiState>
    }

    private fun dev.phonecode.app.agent.ChatViewModel.historyField() =
        javaClass.getDeclaredField("history").apply { isAccessible = true }

    private fun dev.phonecode.app.agent.ChatViewModel.sessionStore(): SessionStore {
        val field = javaClass.getDeclaredField("sessionStore").apply { isAccessible = true }
        return field.get(this) as SessionStore
    }

    @Suppress("UNCHECKED_CAST")
    private fun dev.phonecode.app.agent.ChatViewModel.restoreLines(
        messages: List<ChatMessage>,
    ): List<ChatLine> {
        val method = javaClass.declaredMethods.single { it.name == "toChatLines" }.apply {
            isAccessible = true
        }
        return method.invoke(this, messages) as List<ChatLine>
    }
}
