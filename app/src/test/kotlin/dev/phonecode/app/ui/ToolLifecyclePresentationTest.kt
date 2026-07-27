package dev.phonecode.app.ui

import dev.phonecode.app.agent.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLifecyclePresentationTest {
    private fun action(tool: String, status: ToolStatus): String {
        val method = Class.forName("dev.phonecode.app.ui.chat.ChatScreenKt")
            .getDeclaredMethod("toolAction", String::class.java, ToolStatus::class.java)
            .apply { isAccessible = true }
        return method.invoke(null, tool, status) as String
    }

    @Test
    fun failedToolsNeverUsePastTenseSuccessLabels() {
        assertEquals("Write failed", action("write", ToolStatus.ERROR))
        assertEquals("Command failed", action("bash", ToolStatus.ERROR))
        assertEquals("Git operation failed", action("git_push", ToolStatus.ERROR))
    }
}
