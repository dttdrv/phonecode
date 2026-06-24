package dev.phonecode.tools.todo

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The two todo tools, sharing one [store] so reads see prior writes. */
fun todoTools(store: TodoStore): List<Tool> = listOf(TodoWriteTool(store), TodoReadTool(store))

private fun List<TodoItem>.render(): String =
    if (isEmpty()) "(no todos)" else joinToString("\n") { "- [${it.status.wire}] (${it.priority.wire}) ${it.content}" }

/**
 * Replaces the whole todo list (mirrors OpenCode `todowrite`). Use it to plan and track multi-step work:
 * write the full list each time, flipping items to in_progress/completed as you go. Not a file mutation,
 * so it never prompts for permission.
 */
class TodoWriteTool(private val store: TodoStore) : Tool {
    override val name = "todowrite"
    override val description =
        "Create or update your task list for the current work. Pass the COMPLETE list every time (it " +
            "replaces the previous one). Mark exactly one item in_progress while you work on it, and flip " +
            "items to completed as you finish. Use for any multi-step task so the user can follow along."
    override val promptSnippet = "track multi-step work as a todo list (write the full list each time)"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                put("description", "The complete todo list.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") { put("type", "string"); put("description", "What the task is.") }
                        putJsonObject("status") {
                            put("type", "string")
                            put("description", "pending | in_progress | completed | cancelled")
                        }
                        putJsonObject("priority") {
                            put("type", "string")
                            put("description", "high | medium | low")
                        }
                        putJsonObject("id") { put("type", "string"); put("description", "Stable id (optional).") }
                    }
                    put("required", buildJsonArray { add("content") })
                    put("additionalProperties", false)
                }
            }
        }
        put("required", buildJsonArray { add("todos") })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val array = args["todos"] as? JsonArray ?: return ToolResult("todowrite: missing 'todos' array", isError = true)
        val items = array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = (obj["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapIndexedNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: (index + 1).toString()
            TodoItem(
                id = id,
                content = content,
                status = TodoStatus.from((obj["status"] as? JsonPrimitive)?.content),
                priority = TodoPriority.from((obj["priority"] as? JsonPrimitive)?.content),
            )
        }
        store.replace(items)
        val remaining = items.count { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS }
        return ToolResult("$remaining todo(s) remaining of ${items.size}:\n${items.render()}")
    }
}

/** Returns the current todo list (mirrors OpenCode `todoread`). */
class TodoReadTool(private val store: TodoStore) : Tool {
    override val name = "todoread"
    override val description = "Read your current task list."
    override val promptSnippet = "read your current todo list"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
        ToolResult(store.snapshot().render())
}
