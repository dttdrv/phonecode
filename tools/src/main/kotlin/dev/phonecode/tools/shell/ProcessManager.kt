package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProcessManager(
    private val shellProvider: (String) -> List<String>,
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
    private val onStarted: (String) -> Unit = {},
    private val onStopped: (String) -> Unit = {},
) {
    private class LogBuffer {
        private val value = StringBuilder()

        @Synchronized
        fun append(chars: CharArray, count: Int) {
            value.append(chars, 0, count)
            if (value.length > MAX_LOG) value.delete(0, value.length - MAX_LOG)
        }

        @Synchronized
        fun read(): String = value.toString()
    }

    private data class Session(
        val id: String,
        val command: String,
        val startedAt: Long,
        val process: Process,
        val output: LogBuffer = LogBuffer(),
        val exitCode: AtomicInteger = AtomicInteger(EXIT_UNKNOWN),
        val released: AtomicBoolean = AtomicBoolean(false),
    )

    private val sequence = AtomicInteger()
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun start(command: String, workspacePath: String): ToolResult = withContext(Dispatchers.IO) {
        val commandEnvironment = environmentProvider()
        val process = runCatching {
            ProcessBuilder(shellProvider(workspacePath) + command)
                .directory(if ("PROOT_LOADER" in commandEnvironment) File("/") else File(workspacePath))
                .redirectErrorStream(true)
                .apply { environment().putAll(commandEnvironment) }
                .start()
        }.getOrElse { return@withContext ToolResult("background process failed: ${it.message}", true) }
        val id = "proc-${sequence.incrementAndGet()}"
        val session = Session(id, command, System.currentTimeMillis(), process)
        sessions[id] = session
        runCatching { onStarted(id) }
        watch(session)
        prune()
        delay(350)
        if (!process.isAlive) {
            finish(session, runCatching { process.exitValue() }.getOrDefault(-1))
            return@withContext ToolResult(render(session), true)
        }
        ToolResult(
            "Started $id in the background.\nCommand: ${command.take(240)}\n" +
                "Use process action=output session_id=$id for logs or action=stop to stop it.",
        )
    }

    fun list(): ToolResult {
        val items = sessions.values.sortedBy { it.startedAt }
        if (items.isEmpty()) return ToolResult("No managed background processes.")
        return ToolResult(items.joinToString("\n") { "${it.id} ${status(it)} ${it.command.take(160)}" })
    }

    fun output(id: String): ToolResult {
        val session = sessions[id] ?: return ToolResult("Unknown process session: $id", true)
        return ToolResult(render(session))
    }

    fun input(id: String, data: String, appendNewline: Boolean = true): ToolResult {
        val session = sessions[id] ?: return ToolResult("Unknown process session: $id", true)
        if (!session.process.isAlive) return ToolResult("Process session is not running: $id", true)
        return runCatching {
            session.process.outputStream.writer().apply {
                write(data)
                if (appendNewline) write("\n")
                flush()
            }
            ToolResult("Sent input to $id.")
        }.getOrElse { ToolResult("Unable to send input to $id: ${it.message}", true) }
    }

    suspend fun stop(id: String): ToolResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: return@withContext ToolResult("Unknown process session: $id", true)
        if (session.process.isAlive) {
            session.process.destroy()
            if (!session.process.waitFor(2, TimeUnit.SECONDS)) {
                session.process.destroyForcibly()
                session.process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        finish(session, runCatching { session.process.exitValue() }.getOrDefault(-1))
        ToolResult("Stopped $id.\n${session.output.read().ifBlank { "(no output)" }}")
    }

    private fun watch(session: Session) {
        Thread({
            runCatching {
                session.process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        session.output.append(buffer, count)
                    }
                }
            }
            finish(session, runCatching { session.process.waitFor() }.getOrDefault(-1))
        }, "phonecode-${session.id}").apply { isDaemon = true }.start()
    }

    private fun finish(session: Session, exitCode: Int) {
        session.exitCode.compareAndSet(EXIT_UNKNOWN, exitCode)
        if (session.released.compareAndSet(false, true)) runCatching { onStopped(session.id) }
    }

    private fun render(session: Session): String =
        "${session.id} ${status(session)}\n${session.output.read().ifBlank { "(no output yet)" }}"

    private fun status(session: Session): String = when {
        session.process.isAlive -> "running"
        else -> "exited (${session.exitCode.get().takeUnless { it == EXIT_UNKNOWN } ?: runCatching { session.process.exitValue() }.getOrDefault(-1)})"
    }

    private fun prune() {
        if (sessions.size <= MAX_SESSIONS) return
        sessions.values
            .filterNot { it.process.isAlive }
            .sortedBy { it.startedAt }
            .take(sessions.size - MAX_SESSIONS)
            .forEach { sessions.remove(it.id, it) }
    }

    private companion object {
        const val MAX_LOG = 48_000
        const val MAX_SESSIONS = 24
        const val EXIT_UNKNOWN = Int.MIN_VALUE
    }
}
