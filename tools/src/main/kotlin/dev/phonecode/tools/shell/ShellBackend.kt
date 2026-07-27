package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolResult

data class ShellBackendStatus(
    val available: Boolean,
    val detail: String,
)

/**
 * Runtime-neutral command boundary used by the bash and process tools.
 *
 * Implementations own command execution and background-session lifecycle. The tools only validate
 * model input and preserve the active workspace boundary.
 */
interface ShellBackend {
    fun status(workspacePath: String): ShellBackendStatus

    suspend fun execute(
        command: String,
        workspacePath: String,
        timeoutSeconds: Int,
    ): ToolResult

    suspend fun start(command: String, workspacePath: String): ToolResult

    fun list(workspacePath: String? = null): ToolResult

    fun output(
        sessionId: String,
        workspacePath: String? = null,
        maxChars: Int = 12_000,
    ): ToolResult

    fun input(
        sessionId: String,
        data: String,
        appendNewline: Boolean = true,
        workspacePath: String? = null,
    ): ToolResult

    suspend fun stop(sessionId: String, workspacePath: String? = null): ToolResult

    suspend fun stopWorkspace(workspacePath: String)

    fun stopAll()
}
