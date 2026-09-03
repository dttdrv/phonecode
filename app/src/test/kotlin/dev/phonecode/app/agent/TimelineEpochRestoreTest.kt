package dev.phonecode.app.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEpochRestoreTest {
    @Test
    fun everyRestoredTimelineReplacementInvalidatesThePriorTimelineEpoch() {
        val source = File("src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt").readText()

        assertEpochBump(
            source.substringAfter("val lines = restored.toChatLines()")
                .substringBefore("if (interrupted)"),
            "startup restore",
        )
        assertEpochBump(
            source.substringAfter("fun switchSession(id: String)")
                .substringAfter("lines = restored.toChatLines()")
                .substringBefore("true to activeProjectId"),
            "session restore",
        )
        assertEpochBump(
            source.substringAfter("lines = restored.messages.toChatLines()")
                .substringBefore("reloadProviders()"),
            "backup import",
        )
    }

    private fun assertEpochBump(block: String, restorePath: String) {
        assertTrue(
            "$restorePath must reset timeline identity before restored lines render",
            block.contains("timelineEpoch = it.timelineEpoch + 1"),
        )
    }
}
