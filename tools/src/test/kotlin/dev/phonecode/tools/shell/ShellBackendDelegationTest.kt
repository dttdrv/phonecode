package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBackendDelegationTest {
    private val context = object : ToolContext {
        override val workspacePath = "/workspace/project"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    @Test
    fun foregroundCommandDelegatesExactRequest() = runBlocking {
        val backend = RecordingShellBackend()
        val result = ShellTool(backend).execute(
            buildJsonObject {
                put("command", "printf ready")
                put("timeout_s", 91)
            },
            context,
        )

        assertFalse(result.output, result.isError)
        assertEquals(
            listOf("execute:printf ready:/workspace/project:91"),
            backend.calls,
        )
    }

    @Test
    fun backgroundCommandUsesStartOnly() = runBlocking {
        val backend = RecordingShellBackend()
        val result = ShellTool(backend).execute(
            buildJsonObject {
                put("command", "serve")
                put("background", true)
            },
            context,
        )

        assertFalse(result.output, result.isError)
        assertEquals(listOf("start:serve:/workspace/project"), backend.calls)
    }

    @Test
    fun unavailableBackendFailsWithoutAnotherExecutionPath() = runBlocking {
        val detail = "The isolated VM runtime is not available in this release build."
        val result = ShellTool(UnavailableShellBackend(detail)).execute(
            buildJsonObject { put("command", "echo unsafe") },
            context,
        )

        assertTrue(result.isError)
        assertEquals(detail, result.output)
    }

    @Test
    fun processActionsPreserveTheWorkspaceBoundary() = runBlocking {
        val backend = RecordingShellBackend()
        val tool = ProcessTool(backend)

        tool.execute(buildJsonObject { put("action", "list") }, context)
        tool.execute(
            buildJsonObject {
                put("action", "output")
                put("session_id", "proc-4")
                put("tail_chars", 4321)
            },
            context,
        )
        tool.execute(
            buildJsonObject {
                put("action", "input")
                put("session_id", "proc-4")
                put("data", "yes")
                put("append_newline", false)
            },
            context,
        )
        tool.execute(
            buildJsonObject {
                put("action", "stop")
                put("session_id", "proc-4")
            },
            context,
        )

        assertEquals(
            listOf(
                "list:/workspace/project",
                "output:proc-4:/workspace/project:4321",
                "input:proc-4:yes:false:/workspace/project",
                "stop:proc-4:/workspace/project",
            ),
            backend.calls,
        )
    }

    private class RecordingShellBackend : ShellBackend {
        val calls = mutableListOf<String>()

        override fun status(workspacePath: String) = ShellBackendStatus(true, "test")

        override suspend fun execute(
            command: String,
            workspacePath: String,
            timeoutSeconds: Int,
        ): ToolResult {
            calls += "execute:$command:$workspacePath:$timeoutSeconds"
            return ToolResult("executed")
        }

        override suspend fun start(command: String, workspacePath: String): ToolResult {
            calls += "start:$command:$workspacePath"
            return ToolResult("started")
        }

        override fun list(workspacePath: String?): ToolResult {
            calls += "list:$workspacePath"
            return ToolResult("listed")
        }

        override fun output(sessionId: String, workspacePath: String?, maxChars: Int): ToolResult {
            calls += "output:$sessionId:$workspacePath:$maxChars"
            return ToolResult("output")
        }

        override fun input(
            sessionId: String,
            data: String,
            appendNewline: Boolean,
            workspacePath: String?,
        ): ToolResult {
            calls += "input:$sessionId:$data:$appendNewline:$workspacePath"
            return ToolResult("input")
        }

        override suspend fun stop(sessionId: String, workspacePath: String?): ToolResult {
            calls += "stop:$sessionId:$workspacePath"
            return ToolResult("stopped")
        }

        override suspend fun stopWorkspace(workspacePath: String) = Unit

        override fun stopAll() = Unit
    }
}
