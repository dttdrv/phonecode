package dev.phonecode.app.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.tools.todo.TodoItem
import dev.phonecode.tools.todo.TodoPriority
import dev.phonecode.tools.todo.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 35])
class TurnNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun todo(status: TodoStatus) = TodoItem(status.name, "step ${status.name}", status, TodoPriority.MEDIUM)

    private fun build(status: TurnStatus?) = buildTurnNotification(context, status, startedAt = 1_000L)

    private val Notification.ongoing get() = flags and Notification.FLAG_ONGOING_EVENT != 0

    @Test
    fun runningTurnWithStepsIsPromotedDeterminateProgress() {
        val state = ChatUiState(
            isRunning = true,
            todos = listOf(todo(TodoStatus.COMPLETED), todo(TodoStatus.IN_PROGRESS), todo(TodoStatus.PENDING), todo(TodoStatus.CANCELLED)),
        )
        val status = turnStatusOf(state)!!
        assertEquals(TurnStatus(TurnPhase.RUNNING, "step IN_PROGRESS", done = 1, total = 3), status)

        val n = build(status)
        assertEquals("PhoneCode is working", n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertTrue(n.ongoing)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n))
        assertEquals(1, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(3, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_000L, n.`when`)
        assertEquals(1, n.actions.size)
    }

    @Test
    fun approvalIsOngoingPromotedAndIndeterminate() {
        val status = turnStatusOf(ChatUiState(isRunning = true, pendingPermission = PermissionRequest("bash", "rm -rf build")))!!
        assertEquals(TurnPhase.NEEDS_APPROVAL, status.phase)

        val n = build(status)
        assertEquals("PhoneCode needs approval", n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("rm -rf build", n.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(n.ongoing)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(n.deleteIntent != null)
    }

    @Test
    fun finishedAndFailedTurnsAreDismissibleAndUnpromoted() {
        val done = turnStatusOf(ChatUiState(isRunning = false))!!
        val failed = turnStatusOf(ChatUiState(isRunning = false, turnOutcome = TurnOutcome.FAILED))!!
        assertEquals(TurnPhase.DONE, done.phase)
        assertEquals(TurnPhase.FAILED, failed.phase)
        for ((status, title) in listOf(done to "PhoneCode finished", failed to "PhoneCode stopped with an error")) {
            val n = build(status)
            assertEquals(title, n.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertFalse(n.ongoing)
            assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertFalse(NotificationCompat.isRequestPromotedOngoing(n))
            assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertNull(n.deleteIntent)
        }
    }

    @Test
    fun userStopReportsNothingAndProcessOnlyServiceStaysQuiet() {
        assertNull(turnStatusOf(ChatUiState(isRunning = false, turnOutcome = TurnOutcome.STOPPED)))

        val n = build(null)
        assertTrue(n.ongoing)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(n))
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
    }

    @Test
    fun dismissedLiveUpdateIsNotRepostedUntilTheNextTurn() {
        // Merged into the real manifest by androidx.core; Robolectric does not grant it below API 33.
        shadowOf(context as Application).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val manager = context.getSystemService(NotificationManager::class.java)
        fun shownText() = shadowOf(manager).getNotification(1)?.extras?.getCharSequence(Notification.EXTRA_TEXT)
        try {
            TurnService.showTurnStatus(context, TurnStatus(TurnPhase.RUNNING, "first"))
            assertEquals("first", shownText())

            context.sendBroadcast(Intent(TurnService.ACTION_DISMISSED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            manager.cancel(1)
            TurnService.showTurnStatus(context, TurnStatus(TurnPhase.RUNNING, "second"))
            assertNull(shownText())

            TurnService.showTurnStatus(context, null)
            TurnService.showTurnStatus(context, TurnStatus(TurnPhase.RUNNING, "next turn"))
            assertEquals("next turn", shownText())
        } finally {
            TurnService.showTurnStatus(context, null)
            controller.destroy()
        }
    }
}
