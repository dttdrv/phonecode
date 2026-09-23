package dev.phonecode.app.ui

import dev.phonecode.app.agent.ChatLine
import dev.phonecode.app.agent.ToolStatus
import dev.phonecode.app.ui.chat.toolSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSummaryTest {
    private fun step(name: String, status: ToolStatus = ToolStatus.DONE) =
        ChatLine.ToolActivity(id = name + status, name = name, status = status, detail = "")

    @Test fun nativeToolNamesSummarizeByFamily() {
        val run = listOf(step("read_file"), step("read_file"), step("list_files"), step("shell"))
        assertEquals("Read 2 files · listed a folder · ran a command", toolSummary(run))
    }

    @Test fun configurationAndPlanStepsNameTheirPurpose() {
        assertEquals(
            "Listed a folder · checked the configuration · checked the plan",
            toolSummary(listOf(step("list_files"), step("extension_read"), step("todoread"))),
        )
    }

    @Test fun failuresAreCounted() {
        assertEquals("Edited a file · 1 failed", toolSummary(listOf(step("edit_file", ToolStatus.ERROR))))
    }
}
