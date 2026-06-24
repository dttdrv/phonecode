package dev.phonecode.agent

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskToolTest {

    private object Ctx : ToolContext {
        override val workspacePath = "/ws"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test fun delegatesToRunnerAndWrapsResult() = runBlocking {
        var seen: Triple<String, String, String>? = null
        val tool = TaskTool { description, prompt, type -> seen = Triple(description, prompt, type); "subagent says hi" }
        val result = tool.execute(args("""{"description":"do x","prompt":"build it","subagent_type":"general"}"""), Ctx)
        assertFalse(result.isError)
        assertEquals(Triple("do x", "build it", "general"), seen)
        assertTrue(result.output.contains("subagent says hi"))
        assertTrue(result.output.contains("<task description=\"do x\">"))
    }

    @Test fun missingPromptIsError() = runBlocking {
        val result = TaskTool { _, _, _ -> "x" }.execute(args("""{"description":"d"}"""), Ctx)
        assertTrue(result.isError)
    }

    @Test fun runnerFailureBecomesErrorResult() = runBlocking {
        val result = TaskTool { _, _, _ -> throw RuntimeException("boom") }
            .execute(args("""{"description":"d","prompt":"p"}"""), Ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("boom"))
    }

    @Test fun defaultsSubagentTypeToGeneral() = runBlocking {
        var type: String? = null
        TaskTool { _, _, t -> type = t; "ok" }.execute(args("""{"description":"d","prompt":"p"}"""), Ctx)
        assertEquals("general", type)
    }
}
