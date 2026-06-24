package dev.phonecode.tools.todo

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolsTest {

    private object Ctx : ToolContext {
        override val workspacePath = "/tmp"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test fun writeStoresItemsAndReadReturnsThem() = runBlocking {
        val store = TodoStore()
        val result = TodoWriteTool(store).execute(
            args("""{"todos":[{"content":"A","status":"in_progress","priority":"high"},{"content":"B","status":"completed"}]}"""),
            Ctx,
        )
        assertFalse(result.isError)
        assertEquals(2, store.snapshot().size)
        assertEquals(TodoStatus.IN_PROGRESS, store.snapshot()[0].status)
        assertEquals(TodoPriority.HIGH, store.snapshot()[0].priority)
        assertTrue(result.output.contains("1 todo(s) remaining of 2")) // B is completed

        val read = TodoReadTool(store).execute(JsonObject(emptyMap()), Ctx)
        assertTrue(read.output.contains("A"))
        assertTrue(read.output.contains("B"))
    }

    @Test fun writeIsFullReplacement() = runBlocking {
        val store = TodoStore()
        TodoWriteTool(store).execute(args("""{"todos":[{"content":"A"}]}"""), Ctx)
        TodoWriteTool(store).execute(args("""{"todos":[{"content":"C"}]}"""), Ctx)
        assertEquals(listOf("C"), store.snapshot().map { it.content })
    }

    @Test fun defaultsStatusPriorityAndId() = runBlocking {
        val store = TodoStore()
        TodoWriteTool(store).execute(args("""{"todos":[{"content":"X"}]}"""), Ctx)
        val item = store.snapshot().single()
        assertEquals(TodoStatus.PENDING, item.status)
        assertEquals(TodoPriority.MEDIUM, item.priority)
        assertEquals("1", item.id)
    }

    @Test fun missingTodosIsError() = runBlocking {
        assertTrue(TodoWriteTool(TodoStore()).execute(JsonObject(emptyMap()), Ctx).isError)
    }
}
