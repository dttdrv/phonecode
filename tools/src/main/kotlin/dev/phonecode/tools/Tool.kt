package dev.phonecode.tools

import kotlinx.serialization.json.JsonObject

/** The outcome of a tool invocation. [output] is fed back to the model verbatim. */
data class ToolResult(val output: String, val isError: Boolean = false)

/** Ambient context a tool runs in: the workspace, a permission gate, and a question channel. */
interface ToolContext {
    val workspacePath: String

    /** Ask the user to approve a side-effecting action. Returns true if allowed. */
    suspend fun requestPermission(tool: String, summary: String): Boolean

    /**
     * Ask the user one or more questions and suspend until they answer. Returns one [UserAnswer]
     * per question, in order. The default treats every question as unanswered, so a [ToolContext]
     * with no UI (tests, headless) degrades gracefully instead of breaking.
     */
    suspend fun askUser(questions: List<UserQuestion>): List<UserAnswer> =
        questions.map { UserAnswer(it.question, emptyList()) }
}

/** A capability the agent can invoke. [parameters] is a JSON Schema object. */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** Whether this tool mutates state (and should therefore pass through permission). */
    val mutating: Boolean get() = false

    /** True for tools only meaningful while planning (e.g. plan_exit); shown in PLAN mode, hidden in BUILD. */
    val planOnly: Boolean get() = false

    /** If true, the loop serializes this tool's whole batch instead of running it in parallel. */
    val sequential: Boolean get() = mutating

    /** One-line capability summary contributed to the assembled system prompt's tool list. */
    val promptSnippet: String? get() = null

    /** Usage guidelines (bullets) contributed to the system prompt; co-located with the tool. */
    val promptGuidelines: List<String> get() = emptyList()

    suspend fun execute(args: JsonObject, context: ToolContext): ToolResult
}

/** Name-indexed set of tools available to the agent. */
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
}
