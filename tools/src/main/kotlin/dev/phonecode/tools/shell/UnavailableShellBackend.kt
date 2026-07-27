package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolResult

/** Fail-closed backend used until a production command runtime is fully integrated. */
class UnavailableShellBackend(
    private val detail: String,
) : ShellBackend {
    override fun status(workspacePath: String) = ShellBackendStatus(false, detail)

    override suspend fun execute(
        command: String,
        workspacePath: String,
        timeoutSeconds: Int,
    ) = unavailable()

    override suspend fun start(command: String, workspacePath: String) = unavailable()

    override fun list(workspacePath: String?) = unavailable()

    override fun output(sessionId: String, workspacePath: String?, maxChars: Int) = unavailable()

    override fun input(
        sessionId: String,
        data: String,
        appendNewline: Boolean,
        workspacePath: String?,
    ) = unavailable()

    override suspend fun stop(sessionId: String, workspacePath: String?) = unavailable()

    override suspend fun stopWorkspace(workspacePath: String) = Unit

    override fun stopAll() = Unit

    private fun unavailable() = ToolResult(detail, isError = true)
}
