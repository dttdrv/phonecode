package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Current development backend backed by the bundled PRoot/Alpine process runtime.
 *
 * This class deliberately owns every java.lang.Process operation so a future isolated-VM backend
 * can implement [ShellBackend] without pretending a guest process is an Android host process.
 */
class LocalShellBackend(
    private val shellProvider: (String) -> List<String>,
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
    onStarted: (String) -> Unit = {},
    onStopped: (String) -> Unit = {},
    storageDirectory: File? = null,
    private val statusProvider: ((String) -> ShellBackendStatus)? = null,
    processManager: ProcessManager? = null,
) : ShellBackend {
    private val processManager = processManager ?: ProcessManager(
        shellProvider = shellProvider,
        environmentProvider = environmentProvider,
        onStarted = onStarted,
        onStopped = onStopped,
        storageDirectory = storageDirectory,
    )

    override fun status(workspacePath: String): ShellBackendStatus =
        statusProvider?.invoke(workspacePath) ?: runCatching {
            check(shellProvider(workspacePath).isNotEmpty()) { "command runtime is not ready" }
            ShellBackendStatus(true, "private local command runtime")
        }.getOrElse {
            ShellBackendStatus(false, it.message ?: "command runtime is not ready")
        }

    override suspend fun execute(
        command: String,
        workspacePath: String,
        timeoutSeconds: Int,
    ): ToolResult = withContext(Dispatchers.IO) {
        val shell = runCatching { shellProvider(workspacePath) }.getOrElse {
            return@withContext ToolResult(
                "bash: ${it.message ?: "bundled Alpine environment is not ready"}",
                true,
            )
        }
        if (shell.isEmpty()) {
            return@withContext ToolResult("bash: bundled Alpine environment is not ready", true)
        }

        runCatching {
            val commandEnvironment = environmentProvider()
            val managedProcess = startShellProcess(
                shell = shell,
                command = command,
                directory = if ("PROOT_LOADER" in commandEnvironment) File("/") else File(workspacePath),
                environment = commandEnvironment,
            )
            val process = managedProcess.process
            coroutineScope {
                var timedOut = false
                val output = StringBuilder()
                val reader = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.length < MAX_OUTPUT) output.append(buffer, 0, count)
                        }
                    }
                }
                val exit = try {
                    withTimeout(timeoutSeconds * 1_000L) {
                        runInterruptible { process.waitFor() }
                    }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                    -1
                } finally {
                    terminateProcessTree(process, managedProcess.rootPid, managedProcess.processToken)
                }
                reader.await()
                val truncated = output.length >= MAX_OUTPUT
                val body = buildString {
                    append(output.take(MAX_OUTPUT))
                    if (truncated) append("\n... (output truncated at ${MAX_OUTPUT / 1024} KB)")
                    if (timedOut) append("\n(killed after ${timeoutSeconds}s timeout)")
                    if (exit != 0 && !timedOut) append("\n(exit code $exit)")
                }.ifBlank { if (exit == 0) "(no output)" else "(no output, exit code $exit)" }
                ToolResult(body, isError = exit != 0 || timedOut)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ToolResult("bash failed: ${error.message}", isError = true)
        }
    }

    override suspend fun start(command: String, workspacePath: String): ToolResult =
        processManager.start(command, workspacePath)

    override fun list(workspacePath: String?): ToolResult = processManager.list(workspacePath)

    override fun output(sessionId: String, workspacePath: String?, maxChars: Int): ToolResult =
        processManager.output(sessionId, workspacePath, maxChars)

    override fun input(
        sessionId: String,
        data: String,
        appendNewline: Boolean,
        workspacePath: String?,
    ): ToolResult = processManager.input(sessionId, data, appendNewline, workspacePath)

    override suspend fun stop(sessionId: String, workspacePath: String?): ToolResult =
        processManager.stop(sessionId, workspacePath)

    override suspend fun stopWorkspace(workspacePath: String) =
        processManager.stopWorkspace(workspacePath)

    override fun stopAll() = processManager.stopAll()

    private companion object {
        const val MAX_OUTPUT = 48_000
    }
}
