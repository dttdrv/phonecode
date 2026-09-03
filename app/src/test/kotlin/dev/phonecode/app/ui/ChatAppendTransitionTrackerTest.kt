package dev.phonecode.app.ui

import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.ui.chat.ChatAppendTransitionTracker
import dev.phonecode.app.ui.chat.ChatEntryMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppendTransitionTrackerTest {
    @Test
    fun firstAppendToAnEmptyTimelineStartsWithoutWaitingForViewportObservation() {
        val tracker = ChatAppendTransitionTracker()

        tracker.observe("new-session", timelineEpoch = 0, lines = emptyList(), followOutput = true)
        tracker.observe("new-session", timelineEpoch = 0, lines = listOf(user("first")), followOutput = true)

        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 0))
    }

    @Test
    fun visibleAppendStartsOnlyOnceAndThenRetainsItsSettledState() {
        val tracker = ChatAppendTransitionTracker()
        val restored = listOf(user("restored"))

        tracker.observe("session", timelineEpoch = 0, lines = restored, followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = restored + assistant("appended"), followOutput = true)

        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 1))
        tracker.markEntered(index = 1)
        assertEquals(ChatEntryMotion.RETAINED, tracker.motionFor(index = 1))
    }

    @Test
    fun eligibilityLookupDoesNotConsumeEntryBeforeCompositionCommits() {
        val tracker = ChatAppendTransitionTracker()

        tracker.observe("session", timelineEpoch = 0, lines = emptyList(), followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = listOf(user("first")), followOutput = true)

        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 0))
        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 0))
        tracker.markEntered(index = 0)
        assertEquals(ChatEntryMotion.RETAINED, tracker.motionFor(index = 0))
    }

    @Test
    fun appendSurvivesAnInPlaceToolLifecycleUpdateInTheObservedPrefix() {
        val tracker = ChatAppendTransitionTracker()
        val running = tool(status = ToolStatus.RUNNING, detail = "Running")

        tracker.observe("session", timelineEpoch = 0, lines = listOf(running), followOutput = true)
        tracker.observe(
            "session",
            timelineEpoch = 0,
            lines = listOf(
                running.copy(status = ToolStatus.DONE, detail = "Completed"),
                assistant("appended after tool"),
            ),
            followOutput = true,
        )

        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 1))
    }

    @Test
    fun offscreenAppendExpiresWhenRoutineScrollingStopsFollowingOutput() {
        val tracker = ChatAppendTransitionTracker()
        val restored = listOf(user("restored"))

        tracker.observe("session", timelineEpoch = 0, lines = restored, followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = restored + assistant("offscreen"), followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = restored + assistant("offscreen"), followOutput = false)

        assertEquals(ChatEntryMotion.NONE, tracker.motionFor(index = 1))
    }

    @Test
    fun unchangedTimelineDuringTokenUpdatesNeverStartsAnotherEntry() {
        val tracker = ChatAppendTransitionTracker()
        val timeline = listOf(user("prompt"), assistant("settled"))

        tracker.observe("session", timelineEpoch = 0, lines = timeline, followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = timeline, followOutput = true)

        assertEquals(ChatEntryMotion.NONE, tracker.motionFor(index = 1))
    }

    @Test
    fun restoredLinesNeverAnimateAndEpochResetCannotReuseOldIndexState() {
        val tracker = ChatAppendTransitionTracker()
        val original = listOf(user("old"))
        val restored = listOf(user("restored"), assistant("restored tail"))

        tracker.observe("session", timelineEpoch = 0, lines = emptyList(), followOutput = true)
        tracker.observe("session", timelineEpoch = 0, lines = original, followOutput = true)
        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 0))
        tracker.markEntered(index = 0)

        tracker.observe("session", timelineEpoch = 1, lines = restored, followOutput = true)

        assertEquals(ChatEntryMotion.NONE, tracker.motionFor(index = 0))
        assertEquals(ChatEntryMotion.NONE, tracker.motionFor(index = 1))

        tracker.observe(
            "session",
            timelineEpoch = 1,
            lines = restored + assistant("new append"),
            followOutput = true,
        )
        assertEquals(ChatEntryMotion.START, tracker.motionFor(index = 2))
    }

    private fun user(text: String) = ChatLine.User(text)

    private fun assistant(text: String) = ChatLine.Assistant(text)

    private fun tool(status: ToolStatus, detail: String) = ChatLine.ToolActivity(
        id = "tool-1",
        name = "read_file",
        status = status,
        detail = detail,
        input = "README.md",
    )
}
