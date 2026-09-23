package dev.phonecode.app.agent

import dev.phonecode.tools.Tool

/** Tools the native runtime implements itself on Android. */
internal val NATIVE_TOOLS = listOf(
    "read_file", "list_files", "glob_files", "find_files", "grep_files", "edit_file", "write_file", "skill",
)

// The native file tools replace these; external_directory asks per path, which the native
// per-tool approval gate cannot express.
private val NOT_BRIDGED = setOf("read", "write", "edit", "ls", "glob", "grep", "external_directory")
private val SHELL_TOOLS = setOf("bash", "process")

/** Kotlin tools the native runtime advertises through the host bridge. */
internal fun hostToolsFor(tools: List<Tool>, shellAvailable: Boolean): List<Tool> =
    tools.filter { it.name !in NOT_BRIDGED && (shellAvailable || it.name !in SHELL_TOOLS) }

/** The stable system prompt. [shell] describes the command runtime, or is null when there is none. */
internal fun agentSystemPrompt(toolNames: List<String>, shell: String?, instructions: List<String>): String = buildString {
    appendLine("You are PhoneCode, a coding agent running on the user's Android phone.")
    appendLine()
    appendLine("Workspace: the current project folder. File tools take paths relative to its root, for example src/app.py.")
    if (shell != null) {
        appendLine("Shell: bash runs in $shell. The project is mounted at /workspace.")
    } else {
        appendLine("Shell: none in this build. Do not claim to run commands, builds, or tests; tell the user what to run.")
    }
    appendLine("Tools: ${toolNames.joinToString()}. No other tools exist. Read files before editing them. Independent calls made together run in parallel.")
    appendLine("Approval: writes, commands, git changes, MCP calls, and configuration changes may wait for the user. If a call is denied, do not retry it; say what you needed.")
    if ("extension_write" in toolNames) {
        appendLine("Configuration: when the user asks, manage MCP servers, skills, and tool settings with extension_read and extension_write. New or edited MCP servers stay disabled until the user enables them in Settings. Built-in tools can be disabled but not removed.")
    }
    append("Replies: be brief. Report what changed and how you checked it. Never invent file contents, tool results, or verification.")
    instructions.forEach { append("\n\n").append(it) }
}
