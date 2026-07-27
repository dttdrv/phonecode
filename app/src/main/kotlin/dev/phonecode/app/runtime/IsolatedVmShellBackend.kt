package dev.phonecode.app.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.shell.ShellBackend
import dev.phonecode.tools.shell.ShellBackendStatus
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VmShellRuntime(
    val client: VmShellClient,
    private val closeTransport: () -> Unit,
) {
    private val closed = AtomicBoolean()

    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        try {
            client.shutdown()
        } finally {
            closeTransport()
        }
    }

    fun closeNow() {
        if (closed.compareAndSet(false, true)) closeTransport()
    }
}

/**
 * Production shell backend backed exclusively by the isolated QEMU guest.
 *
 * Runtime startup and teardown are serialized, every active command owns an FGS lease, and
 * workspace ownership is checked before session data or control is exposed.
 */
internal class IsolatedVmShellBackend(
    private val runtimeFactory: suspend () -> VmShellRuntime,
    private val acquireForegroundLease: (String) -> Unit,
    private val releaseForegroundLease: (String) -> Unit,
    private val availabilityBlocker: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ShellBackend {
    private val lifecycle = Mutex()
    private val stateLock = Any()
    private val sequence = AtomicLong()
    private val sessions = linkedMapOf<String, SessionRecord>()

    private var runtime: VmShellRuntime? = null
    private var foregroundOperations = 0
    @Volatile private var runtimeFailure: String? = null

    override fun status(workspacePath: String): ShellBackendStatus {
        availabilityBlocker?.let { return ShellBackendStatus(false, it) }
        val failure = runtimeFailure
        return if (failure == null) {
            ShellBackendStatus(true, "verified isolated VM command runtime")
        } else {
            ShellBackendStatus(false, failure)
        }
    }

    override suspend fun execute(
        command: String,
        workspacePath: String,
        timeoutSeconds: Int,
    ): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val lease = "vm-exec:${sequence.incrementAndGet()}"
        try {
            acquireForegroundLease(lease)
        } catch (error: Throwable) {
            return ToolResult(
                "bash: isolated VM foreground service could not start: ${error.safeMessage()}",
                true,
            )
        }
        synchronized(stateLock) { foregroundOperations++ }
        var active: VmShellRuntime? = null
        return try {
            active = ensureRuntime()
            val result = active.client.execute(
                command = command,
                timeoutMillis = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS) * 1_000,
            )
            result.asToolResult()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            active?.let { failRuntime(it, error) }
            ToolResult("bash: isolated VM runtime failed: ${error.safeMessage()}", true)
        } finally {
            synchronized(stateLock) { foregroundOperations-- }
            releaseForegroundLease(lease)
            closeIfIdle()
        }
    }

    override suspend fun start(command: String, workspacePath: String): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val sessionId = "vm-${sequence.incrementAndGet()}"
        val lease = "vm-process:$sessionId"
        try {
            acquireForegroundLease(lease)
        } catch (error: Throwable) {
            return ToolResult(
                "background process failed: isolated VM foreground service could not start: " +
                    error.safeMessage(),
                true,
            )
        }

        var active: VmShellRuntime? = null
        try {
            active = ensureRuntime()
            val guest = active.client.start(command, BACKGROUND_TIMEOUT_MILLIS)
            val record = SessionRecord(
                id = sessionId,
                guestId = guest.id,
                command = command,
                workspacePath = workspacePath,
                lease = lease,
            )
            synchronized(stateLock) {
                sessions[sessionId] = record
                pruneCompletedSessionsLocked()
            }
            watch(active, record)
            return ToolResult(
                "Started $sessionId in the isolated VM.\nCommand: ${command.take(240)}\n" +
                    "Use process action=output session_id=$sessionId for logs or action=stop to stop it.",
            )
        } catch (error: CancellationException) {
            releaseForegroundLease(lease)
            closeIfIdle()
            throw error
        } catch (error: Throwable) {
            releaseForegroundLease(lease)
            active?.let { failRuntime(it, error) }
            closeIfIdle()
            return ToolResult(
                "background process failed: isolated VM runtime failed: ${error.safeMessage()}",
                true,
            )
        }
    }

    override fun list(workspacePath: String?): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val records = synchronized(stateLock) {
            sessions.values
                .filter { workspacePath == null || it.workspacePath == workspacePath }
                .map(SessionRecord::snapshot)
        }
        if (records.isEmpty()) return ToolResult("No managed background processes.")
        return ToolResult(
            records.joinToString("\n") { record ->
                "${record.id} ${record.state.label} ${record.command.take(160)}"
            },
        )
    }

    override fun output(
        sessionId: String,
        workspacePath: String?,
        maxChars: Int,
    ): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val record = ownedSession(sessionId, workspacePath)
            ?: return ToolResult("Unknown process session: $sessionId", true)
        val snapshot = record.snapshot()
        val output = if (snapshot.state == SessionState.RUNNING) {
            val active = synchronized(stateLock) { runtime }
                ?: return ToolResult("Process $sessionId was interrupted because the isolated VM stopped.", true)
            runCatching { active.client.output(record.guestId) }.getOrElse { error ->
                return ToolResult(
                    "Process $sessionId output is unavailable: ${error.safeMessage()}",
                    true,
                )
            }
        } else {
            snapshot.output
        }
        val text = output.output.toString(Charsets.UTF_8).takeLast(maxChars.coerceIn(1_000, MAX_OUTPUT))
        return ToolResult(
            buildString {
                if (text.isNotEmpty()) append(text)
                if (output.truncated) append("\n... (output truncated)")
                when {
                    output.running -> if (isEmpty()) append("(running, no output)")
                    output.status == 0 -> if (isEmpty()) append("(no output)")
                    else -> {
                        if (isNotEmpty()) append('\n')
                        append("(exit code ${output.status ?: -1})")
                    }
                }
            },
            isError = !output.running && output.status != 0,
        )
    }

    override suspend fun input(
        sessionId: String,
        data: String,
        appendNewline: Boolean,
        workspacePath: String?,
    ): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val record = ownedSession(sessionId, workspacePath)
            ?: return ToolResult("Unknown process session: $sessionId", true)
        if (record.snapshot().state != SessionState.RUNNING) {
            return ToolResult("Process $sessionId is not running.", true)
        }
        val bytes = (data + if (appendNewline) "\n" else "").toByteArray()
        if (bytes.size > VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES) {
            return ToolResult(
                "Process input exceeds the ${VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES}-byte VM protocol limit.",
                true,
            )
        }
        val active = synchronized(stateLock) { runtime }
            ?: return ToolResult("Process $sessionId was interrupted because the isolated VM stopped.", true)
        return try {
            active.client.input(record.guestId, bytes)
            ToolResult("Sent ${bytes.size} bytes to $sessionId.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolResult("Process input failed: ${error.safeMessage()}", true)
        }
    }

    override suspend fun stop(sessionId: String, workspacePath: String?): ToolResult {
        availabilityBlocker?.let { return ToolResult(it, true) }
        val record = ownedSession(sessionId, workspacePath)
            ?: return ToolResult("Unknown process session: $sessionId", true)
        val snapshot = record.snapshot()
        if (snapshot.state != SessionState.RUNNING) return snapshot.output.asToolResult()
        val active = synchronized(stateLock) { runtime }
            ?: return ToolResult("Process $sessionId was interrupted because the isolated VM stopped.", true)
        return try {
            val result = active.client.stop(record.guestId)
            finish(record, result)
            ToolResult(
                "Stopped $sessionId.\n" +
                    result.output.toString(Charsets.UTF_8).takeLast(MAX_OUTPUT)
                        .ifBlank { "(no output)" },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failRuntime(active, error)
            ToolResult("Could not stop $sessionId: ${error.safeMessage()}", true)
        } finally {
            closeIfIdle()
        }
    }

    override suspend fun stopWorkspace(workspacePath: String) {
        if (availabilityBlocker != null) return
        val ids = synchronized(stateLock) {
            sessions.values.filter { it.workspacePath == workspacePath }.map { it.id }
        }
        ids.forEach { stop(it, workspacePath) }
    }

    override fun stopAll() {
        val state = synchronized(stateLock) {
            val active = runtime
            runtime = null
            val running = sessions.values.filter { it.state == SessionState.RUNNING }
            running.forEach {
                it.state = SessionState.INTERRUPTED
                it.output = VmSessionOutput(ByteArray(0), false, false, null)
            }
            pruneCompletedSessionsLocked()
            active to running.map { it.lease }
        }
        state.second.forEach(releaseForegroundLease)
        state.first?.let { active ->
            scope.launch {
                runCatching { active.shutdown() }
            }
        }
    }

    private suspend fun ensureRuntime(): VmShellRuntime = lifecycle.withLock {
        runtime?.let { return it }
        val created = runtimeFactory()
        synchronized(stateLock) {
            runtime = created
            runtimeFailure = null
        }
        created
    }

    private suspend fun closeIfIdle() {
        val closing = lifecycle.withLock {
            val idle = synchronized(stateLock) {
                foregroundOperations == 0 && sessions.values.none { it.state == SessionState.RUNNING }
            }
            if (!idle) return@withLock null
            synchronized(stateLock) { runtime.also { runtime = null } }
        }
        closing?.let { active ->
            runCatching { active.shutdown() }.onFailure { error ->
                runtimeFailure = "Isolated VM runtime cleanup failed: ${error.safeMessage()}"
            }
        }
    }

    private suspend fun failRuntime(active: VmShellRuntime, error: Throwable) {
        val leases = lifecycle.withLock {
            synchronized(stateLock) {
                if (runtime !== active) return@withLock emptyList()
                runtime = null
                runtimeFailure = "Isolated VM runtime failed: ${error.safeMessage()}"
                sessions.values
                    .filter { it.state == SessionState.RUNNING }
                    .onEach {
                        it.state = SessionState.INTERRUPTED
                        it.output = VmSessionOutput(ByteArray(0), false, false, null)
                    }
                    .map { it.lease }
                    .also { pruneCompletedSessionsLocked() }
            }
        }
        leases.forEach(releaseForegroundLease)
        active.closeNow()
    }

    private fun watch(active: VmShellRuntime, record: SessionRecord) {
        scope.launch {
            try {
                finish(record, active.client.awaitBackground(record.guestId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failRuntime(active, error)
            } finally {
                closeIfIdle()
            }
        }
    }

    private fun finish(record: SessionRecord, result: VmExecutionResult) {
        val release = synchronized(stateLock) {
            if (record.state != SessionState.RUNNING) return
            record.state = SessionState.EXITED
            record.output = result.asSessionOutput()
            pruneCompletedSessionsLocked()
            true
        }
        if (release) releaseForegroundLease(record.lease)
    }

    private fun ownedSession(id: String, workspacePath: String?): SessionRecord? =
        synchronized(stateLock) {
            sessions[id]?.takeIf { workspacePath == null || it.workspacePath == workspacePath }
        }

    private fun pruneCompletedSessionsLocked() {
        val overflow = sessions.size - MAX_SESSIONS
        if (overflow <= 0) return
        sessions.values
            .filter { it.state != SessionState.RUNNING }
            .take(overflow)
            .forEach { sessions.remove(it.id) }
    }

    private fun VmExecutionResult.asSessionOutput() =
        VmSessionOutput(output, truncated, running = false, status = status)

    private fun VmExecutionResult.asToolResult(): ToolResult {
        val text = output.toString(Charsets.UTF_8)
        return ToolResult(
            buildString {
                append(text.take(MAX_OUTPUT))
                if (truncated || text.length > MAX_OUTPUT) {
                    if (isNotEmpty()) append('\n')
                    append("... (output truncated)")
                }
                if (status != 0) {
                    if (isNotEmpty()) append('\n')
                    append("(exit code $status)")
                }
                if (isEmpty()) append(if (status == 0) "(no output)" else "(no output, exit code $status)")
            },
            isError = status != 0,
        )
    }

    private fun VmSessionOutput.asToolResult(): ToolResult =
        VmExecutionResult(0, status ?: -1, output, truncated).asToolResult()

    private fun Throwable.safeMessage(): String = message?.take(300) ?: javaClass.simpleName

    private class SessionRecord(
        val id: String,
        val guestId: Long,
        val command: String,
        val workspacePath: String,
        val lease: String,
        var state: SessionState = SessionState.RUNNING,
        var output: VmSessionOutput = VmSessionOutput(ByteArray(0), false, true, null),
    ) {
        fun snapshot() = SessionSnapshot(id, command, state, output)
    }

    private data class SessionSnapshot(
        val id: String,
        val command: String,
        val state: SessionState,
        val output: VmSessionOutput,
    )

    private enum class SessionState(val label: String) {
        RUNNING("running"),
        EXITED("exited"),
        INTERRUPTED("interrupted"),
    }

    companion object {
        private const val MAX_TIMEOUT_SECONDS = 1_800
        private const val BACKGROUND_TIMEOUT_MILLIS = VmGuestProtocol.MAX_TIMEOUT_MILLIS
        private const val MAX_OUTPUT = VmGuestProtocol.MAX_RETAINED_OUTPUT_BYTES
        private const val MAX_SESSIONS = 24

        fun create(
            context: Context,
            artifactStore: VmArtifactStore,
            acquireForegroundLease: (String) -> Unit,
            releaseForegroundLease: (String) -> Unit,
        ): IsolatedVmShellBackend {
            val controller = IsolatedVmController(context)
            return IsolatedVmShellBackend(
                runtimeFactory = {
                    val session = artifactStore.openVerified().use { artifacts ->
                        controller.start(
                            artifacts.kernel,
                            artifacts.initramfs,
                            artifacts.systemImage,
                        )
                    }
                    val transport = try {
                        VmControllerTransport(session)
                    } catch (error: Throwable) {
                        session.close()
                        throw error
                    }
                    try {
                        val client = VmGuestClient(transport.input, transport.output)
                        client.connect(secureNonce())
                        VmShellRuntime(client, transport::close)
                    } catch (error: Throwable) {
                        transport.close()
                        throw error
                    }
                },
                acquireForegroundLease = acquireForegroundLease,
                releaseForegroundLease = releaseForegroundLease,
                availabilityBlocker = WORKSPACE_BRIDGE_UNAVAILABLE,
            )
        }

        private fun secureNonce(): String =
            ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        internal const val WORKSPACE_BRIDGE_UNAVAILABLE =
            "Isolated VM project workspace transport is not available in this release build."
    }
}

private class VmControllerTransport(
    private val session: IsolatedVmSession,
) : Closeable {
    private val closed = AtomicBoolean()
    val input = ParcelFileDescriptor.AutoCloseInputStream(
        ParcelFileDescriptor.dup(session.control.fileDescriptor),
    )
    val output = ParcelFileDescriptor.AutoCloseOutputStream(
        ParcelFileDescriptor.dup(session.control.fileDescriptor),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { session.close() }
    }
}
