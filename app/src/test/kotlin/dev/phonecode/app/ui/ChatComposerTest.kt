package dev.phonecode.app.ui.chat

import androidx.compose.ui.unit.dp
import dev.phonecode.provider.domain.MessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerTest {
    @Test
    fun emptyStateHasNoPrimaryAction() {
        assertEquals(
            ComposerVisualState.EMPTY,
            composerVisualState(
                enabled = true,
                loading = false,
                running = false,
                sendable = false,
                queueAllowed = false,
            ),
        )
    }

    @Test
    fun readyStateShowsSend() {
        assertEquals(
            ComposerVisualState.READY,
            composerVisualState(
                enabled = true,
                loading = false,
                running = false,
                sendable = true,
                queueAllowed = false,
            ),
        )
    }

    @Test
    fun runningStateShowsStopWithoutAQueueAction() {
        assertEquals(
            ComposerVisualState.RUNNING,
            composerVisualState(
                enabled = true,
                loading = false,
                running = true,
                sendable = false,
                queueAllowed = true,
            ),
        )
    }

    @Test
    fun runningStateWithSendableDraftAndQueueShowsQueueAction() {
        assertEquals(
            ComposerVisualState.RUNNING_WITH_QUEUE,
            composerVisualState(
                enabled = true,
                loading = false,
                running = true,
                sendable = true,
                queueAllowed = true,
            ),
        )
    }

    @Test
    fun disabledStateWinsOverEveryActionState() {
        assertEquals(
            ComposerVisualState.DISABLED,
            composerVisualState(
                enabled = false,
                loading = false,
                running = true,
                sendable = true,
                queueAllowed = true,
            ),
        )
        assertEquals(
            ComposerVisualState.DISABLED,
            composerVisualState(
                enabled = true,
                loading = true,
                running = true,
                sendable = true,
                queueAllowed = true,
            ),
        )
    }

    @Test
    fun primarySlotAndMultilineBoundsStayStableAcrossVisualStates() {
        assertEquals(56.dp, ComposerHeight)
        assertEquals(48.dp, ComposerActionTarget)
        assertEquals(40.dp, ComposerActionVisual)
        assertEquals(6, ComposerMaxLines)

        ComposerVisualState.entries.forEach { state ->
            assertEquals(48.dp, composerActionSlots(state).primaryWidth)
        }
        assertNull(composerActionSlots(ComposerVisualState.EMPTY).queueWidth)
        assertNull(composerActionSlots(ComposerVisualState.READY).queueWidth)
        assertNull(composerActionSlots(ComposerVisualState.RUNNING).queueWidth)
        assertNull(composerActionSlots(ComposerVisualState.DISABLED).queueWidth)
        assertEquals(48.dp, composerActionSlots(ComposerVisualState.RUNNING_WITH_QUEUE).queueWidth)
    }

    @Test
    fun queueEligibilityMatchesTheRuntimePayloadContract() {
        assertTrue(canQueueComposerDraft("Queue this", emptyList()))
        assertFalse(canQueueComposerDraft("  ", emptyList()))
        assertFalse(
            canQueueComposerDraft(
                "Queue this",
                listOf(MessagePart.Image("image/png", DECODABLE_PNG)),
            ),
        )
    }

    private companion object {
        const val DECODABLE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL7XQAAAABJRU5ErkJggg=="
    }
}
