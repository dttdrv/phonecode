package dev.phonecode.app.runtime

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal enum class VmGuestSignal {
    INT,
    KILL,
    TERM,
}

internal data class VmExecutionResult(
    val id: Long,
    val status: Int,
    val output: ByteArray,
    val truncated: Boolean,
)

internal data class VmBackgroundSession(
    val id: Long,
    val pid: Int,
)

internal data class VmSessionOutput(
    val output: ByteArray,
    val truncated: Boolean,
    val running: Boolean,
    val status: Int?,
)

internal class VmGuestRemoteException(
    val requestId: Long,
    val code: String,
    message: String,
) : VmProtocolException(message)

internal interface VmShellClient {
    suspend fun execute(command: String, timeoutMillis: Int): VmExecutionResult
    suspend fun start(command: String, timeoutMillis: Int): VmBackgroundSession
    fun output(id: Long): VmSessionOutput
    suspend fun input(id: Long, bytes: ByteArray, eof: Boolean = false)
    suspend fun signal(id: Long, signal: VmGuestSignal)
    suspend fun stop(id: Long): VmExecutionResult
    suspend fun awaitBackground(id: Long): VmExecutionResult
    suspend fun shutdown()
}

/**
 * Stateful host endpoint for the framed guest protocol.
 *
 * Exactly one coroutine reads protocol frames. All request transitions are serialized through
 * [stateMutex], while writes use a separate mutex so stdin and signals cannot interleave frames.
 */
internal class VmGuestClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val startupTimeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS,
    private val cleanupWriteTimeoutMillis: Long = DEFAULT_CLEANUP_WRITE_TIMEOUT_MILLIS,
    private val beforeBackgroundWrite: suspend () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VmShellClient {
    private val stateMutex = Mutex()
    private val outputMutex = Mutex()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val ioDispatcher = dispatcher
    private val ready = CompletableDeferred<Unit>()
    private val requests = mutableMapOf<Long, Request>()
    private val backgrounds = ConcurrentHashMap<Long, BackgroundRecord>()

    private var connectionState = ConnectionState.NEW
    private var expectedNonce: String? = null
    private var transportError: VmProtocolException? = null
    private var nextRequestId = 1L
    private var readerJob: Job? = null

    suspend fun connect(expectedNonce: String) {
        val hello = VmGuestProtocol.hello(expectedNonce)
        stateMutex.withLock {
            when (connectionState) {
                ConnectionState.NEW -> {
                    connectionState = ConnectionState.CONNECTING
                    this.expectedNonce = expectedNonce
                    readerJob = scope.launch { readFrames() }
                }
                ConnectionState.CONNECTED -> return
                ConnectionState.CONNECTING -> throw VmProtocolException("VM guest connection is already starting")
                ConnectionState.CLOSED -> throw closedError()
            }
        }

        try {
            writeFrame(hello)
            withTimeout(startupTimeoutMillis) {
                ready.await()
            }
        } catch (error: TimeoutCancellationException) {
            val timeout = VmProtocolException("VM READY timed out", error)
            terminateAndJoin(timeout)
            throw timeout
        } catch (error: CancellationException) {
            terminateAndJoin(VmProtocolException("VM guest connection was cancelled", error))
            throw error
        } catch (error: Throwable) {
            val protocolError = error.asProtocolError("VM guest connection failed")
            terminateAndJoin(protocolError)
            throw protocolError
        }
    }

    override suspend fun execute(
        command: String,
        timeoutMillis: Int,
    ): VmExecutionResult {
        val request = openRequest(background = false)
        try {
            writeFrame(VmGuestProtocol.exec(request.id, command, timeoutMillis, background = false))
            return request.terminal.await()
        } catch (error: CancellationException) {
            cancelRequest(request)
            throw error
        } catch (error: Throwable) {
            abandonIfLive(request, error)
            throw error
        }
    }

    override suspend fun start(
        command: String,
        timeoutMillis: Int,
    ): VmBackgroundSession {
        val request = openRequest(background = true)
        backgrounds[request.id] = BackgroundRecord(request.output, request.terminal)
        try {
            writeFrame(VmGuestProtocol.exec(request.id, command, timeoutMillis, background = true))
            return request.started.await()
        } catch (error: CancellationException) {
            cancelRequest(request)
            backgrounds.remove(request.id)
            throw error
        } catch (error: Throwable) {
            abandonIfLive(request, error)
            backgrounds.remove(request.id)
            throw error
        }
    }

    override fun output(id: Long): VmSessionOutput {
        val record = backgrounds[id] ?: throw VmProtocolException("VM background request $id is unknown")
        record.error?.let { throw it }
        val result = record.result
        return VmSessionOutput(
            output = record.output.bytes(),
            truncated = record.output.truncated || result?.truncated == true,
            running = result == null,
            status = result?.status,
        )
    }

    override suspend fun input(
        id: Long,
        bytes: ByteArray,
        eof: Boolean,
    ) {
        writeLiveBackgroundFrame(id, VmGuestProtocol.stdin(id, bytes, eof))
    }

    override suspend fun signal(id: Long, signal: VmGuestSignal) {
        writeLiveBackgroundFrame(id, VmGuestProtocol.signal(id, signal.name))
    }

    override suspend fun stop(id: Long): VmExecutionResult {
        val record = backgrounds[id] ?: throw VmProtocolException("VM background request $id is unknown")
        record.error?.let { throw it }
        record.result?.let { return it }

        val resolution = withContext(NonCancellable) {
            val attempted = runCatching {
                withTimeout(cleanupWriteTimeoutMillis) {
                    val encoded = VmGuestProtocol.encode(
                        VmGuestProtocol.signal(id, VmGuestSignal.TERM.name),
                    )
                    outputMutex.withLock {
                        val current = stateMutex.withLock {
                            val live = requests[id]
                            if (live == null) {
                                record.error?.let { throw it }
                                record.result?.let {
                                    return@withLock StopResolution(completed = it)
                                }
                                throw VmProtocolException("VM background request $id is not active")
                            }
                            if (!live.background) {
                                throw VmProtocolException("VM request $id is not a background request")
                            }
                            when (live.state) {
                                RequestState.ACTIVE -> {
                                    live.state = RequestState.CANCELLING
                                    StopResolution(request = live, shouldSignal = true)
                                }
                                RequestState.CANCELLING ->
                                    StopResolution(request = live, shouldSignal = false)
                                RequestState.EXITED, RequestState.FAILED ->
                                    throw VmProtocolException("VM background request $id is not active")
                            }
                        }
                        if (current.shouldSignal) writeEncodedFrame(encoded)
                        current
                    }
                }
            }
            attempted.getOrElse { error ->
                val protocolError = error.asProtocolError("VM TERM delivery failed")
                terminateAndJoin(protocolError)
                throw protocolError
            }
        }
        resolution.completed?.let { return it }
        return requireNotNull(resolution.request).terminal.await()
    }

    override suspend fun awaitBackground(id: Long): VmExecutionResult {
        val record = backgrounds[id]
            ?: throw VmProtocolException("VM background request $id is unknown")
        return record.terminal.await()
    }

    override suspend fun shutdown() {
        val shouldWrite = stateMutex.withLock {
            when (connectionState) {
                ConnectionState.CLOSED -> false
                ConnectionState.CONNECTED -> true
                ConnectionState.NEW, ConnectionState.CONNECTING ->
                    throw VmProtocolException("VM guest is not connected")
            }
        }
        if (!shouldWrite) return
        writeFrame(VmGuestProtocol.shutdown())
        terminateAndJoin(VmProtocolException("VM guest client shut down"))
    }

    private suspend fun openRequest(background: Boolean): Request = stateMutex.withLock {
        ensureConnected()
        if (nextRequestId <= 0L) throw VmProtocolException("VM request id space is exhausted")
        val request = Request(nextRequestId++, background)
        requests[request.id] = request
        request
    }

    private suspend fun writeLiveBackgroundFrame(id: Long, frame: JsonObject) {
        val encoded = VmGuestProtocol.encode(frame)
        outputMutex.withLock {
            stateMutex.withLock {
                ensureConnected()
                val request = requests[id]
                    ?: throw VmProtocolException("VM background request $id is not active")
                if (!request.background) {
                    throw VmProtocolException("VM request $id is not a background request")
                }
                if (request.state != RequestState.ACTIVE) {
                    throw VmProtocolException("VM background request $id is stopping")
                }
            }
            beforeBackgroundWrite()
            writeEncodedFrame(encoded)
        }
    }

    private suspend fun cancelRequest(request: Request) = withContext(NonCancellable) {
        val attempted = runCatching {
            withTimeout(cleanupWriteTimeoutMillis) {
                val encoded = VmGuestProtocol.encode(
                    VmGuestProtocol.signal(request.id, VmGuestSignal.TERM.name),
                )
                outputMutex.withLock {
                    val shouldSignal =
                        stateMutex.withLock {
                            val live = requests[request.id]
                            if (live === request && live.state == RequestState.ACTIVE) {
                                live.state = RequestState.CANCELLING
                                true
                            } else {
                                false
                            }
                        }
                    if (shouldSignal) writeEncodedFrame(encoded)
                }
            }
        }
        attempted.exceptionOrNull()?.let { error ->
            terminateAndJoin(error.asProtocolError("VM cancellation TERM delivery failed"))
        }
    }

    private suspend fun abandonIfLive(request: Request, error: Throwable) {
        stateMutex.withLock {
            if (requests.remove(request.id) === request) {
                request.state = RequestState.FAILED
                val protocolError = error.asProtocolError("VM request ${request.id} failed")
                request.started.completeExceptionally(protocolError)
                request.terminal.completeExceptionally(protocolError)
                backgrounds[request.id]?.error = protocolError
            }
        }
    }

    private suspend fun writeFrame(frame: JsonObject) {
        val encoded = VmGuestProtocol.encode(frame)
        outputMutex.withLock {
            writeEncodedFrame(encoded)
        }
    }

    private suspend fun writeEncodedFrame(encoded: ByteArray) {
        try {
            runInterruptible(ioDispatcher) {
                output.write(encoded)
                output.flush()
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            val protocolError = VmProtocolException("VM guest transport write failed", error)
            terminateAndJoin(protocolError)
            throw protocolError
        }
    }

    private suspend fun readFrames() {
        try {
            while (true) {
                val frame = runInterruptible(ioDispatcher) { VmGuestProtocol.read(input) }
                    ?: throw VmProtocolException("VM guest transport reached EOF")
                handleFrame(frame)
            }
        } catch (error: CancellationException) {
            // shutdown/terminate owns completion and descriptor closure
        } catch (error: Throwable) {
            terminate(error.asProtocolError("VM guest transport read failed"))
        }
    }

    private suspend fun handleFrame(frame: JsonObject) {
        when (frame.stringField("type")) {
            "ready" -> handleReady(frame)
            "started" -> handleStarted(frame)
            "output" -> handleOutput(frame)
            "exit" -> handleExit(frame)
            "error" -> handleError(frame)
            else -> throw VmProtocolException(
                "Unexpected host-bound VM frame '${frame.stringField("type")}'",
            )
        }
    }

    private suspend fun handleReady(frame: JsonObject) {
        val nonce = stateMutex.withLock {
            if (connectionState != ConnectionState.CONNECTING) {
                throw VmProtocolException("VM READY was duplicate or unexpected")
            }
            requireNotNull(expectedNonce)
        }
        VmGuestProtocol.validateReady(frame, nonce)
        stateMutex.withLock {
            if (connectionState != ConnectionState.CONNECTING) {
                throw VmProtocolException("VM READY was duplicate or unexpected")
            }
            connectionState = ConnectionState.CONNECTED
            ready.complete(Unit)
        }
    }

    private suspend fun handleStarted(frame: JsonObject) {
        val id = frame.longField("id")
        val pid = frame.intField("pid")
        stateMutex.withLock {
            ensureConnected()
            val request = requests[id]
                ?: throw VmProtocolException("VM started frame references unknown request $id")
            if (!request.background || request.started.isCompleted) {
                throw VmProtocolException("VM started frame is duplicate or unexpected for request $id")
            }
            request.started.complete(VmBackgroundSession(id, pid))
        }
    }

    private suspend fun handleOutput(frame: JsonObject) {
        val output = VmGuestProtocol.validateOutput(frame)
        stateMutex.withLock {
            ensureConnected()
            val request = requests[output.id]
                ?: throw VmProtocolException("VM output frame references unknown request ${output.id}")
            if (output.sequence != request.nextSequence) {
                throw VmProtocolException(
                    "VM output sequence mismatch for request ${output.id}: " +
                        "expected ${request.nextSequence}, received ${output.sequence}",
                )
            }
            request.nextSequence++
            request.output.append(output.bytes)
        }
    }

    private suspend fun handleExit(frame: JsonObject) {
        val id = frame.longField("id")
        val status = frame.intField("status")
        val guestTruncated = frame.booleanField("truncated")
        stateMutex.withLock {
            ensureConnected()
            val request = requests[id]
                ?: throw VmProtocolException("VM exit frame references unknown request $id")
            if (request.background && !request.started.isCompleted && request.state == RequestState.ACTIVE) {
                throw VmProtocolException("VM background request $id exited before started")
            }
            val result = VmExecutionResult(
                id = id,
                status = status,
                output = request.output.bytes(),
                truncated = guestTruncated || request.output.truncated,
            )
            request.state = RequestState.EXITED
            requests.remove(id)
            backgrounds[id]?.result = result
            request.terminal.complete(result)
        }
    }

    private suspend fun handleError(frame: JsonObject) {
        val id = frame.longField("id")
        val error = VmGuestRemoteException(
            requestId = id,
            code = frame.stringField("code"),
            message = frame.stringField("message"),
        )
        if (id == 0L) throw error
        stateMutex.withLock {
            ensureConnected()
            val request = requests.remove(id)
                ?: throw VmProtocolException("VM error frame references unknown request $id")
            request.state = RequestState.FAILED
            backgrounds[id]?.error = error
            request.started.completeExceptionally(error)
            request.terminal.completeExceptionally(error)
        }
    }

    private suspend fun terminate(error: VmProtocolException) {
        val pending = stateMutex.withLock {
            if (connectionState == ConnectionState.CLOSED) return@withLock null
            connectionState = ConnectionState.CLOSED
            transportError = error
            ready.completeExceptionally(error)
            val active = requests.values.toList()
            requests.clear()
            active.forEach { request ->
                request.state = RequestState.FAILED
                backgrounds[request.id]?.error = error
            }
            active
        } ?: return

        pending.forEach { request ->
            request.started.completeExceptionally(error)
            request.terminal.completeExceptionally(error)
        }
        runCatching { input.close() }
        runCatching { output.close() }
        supervisor.cancel()
    }

    private suspend fun terminateAndJoin(error: VmProtocolException) =
        withContext(NonCancellable) {
            terminate(error)
            readerJob?.join()
        }

    private fun ensureConnected() {
        when (connectionState) {
            ConnectionState.CONNECTED -> Unit
            ConnectionState.CLOSED -> throw closedError()
            ConnectionState.NEW, ConnectionState.CONNECTING ->
                throw VmProtocolException("VM guest is not connected")
        }
    }

    private fun closedError(): VmProtocolException =
        transportError ?: VmProtocolException("VM guest transport is closed")

    private fun Throwable.asProtocolError(prefix: String): VmProtocolException =
        this as? VmProtocolException ?: VmProtocolException("$prefix: ${message ?: javaClass.simpleName}", this)

    private fun JsonObject.stringField(name: String): String =
        requireNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.longField(name: String): Long =
        requireNotNull(this[name]).jsonPrimitive.long

    private fun JsonObject.intField(name: String): Int =
        requireNotNull(this[name]).jsonPrimitive.int

    private fun JsonObject.booleanField(name: String): Boolean =
        requireNotNull(this[name]).jsonPrimitive.boolean

    private class Request(
        val id: Long,
        val background: Boolean,
        val output: VmOutputWindow = VmOutputWindow(),
        val started: CompletableDeferred<VmBackgroundSession> = CompletableDeferred(),
        val terminal: CompletableDeferred<VmExecutionResult> = CompletableDeferred(),
        var state: RequestState = RequestState.ACTIVE,
        var nextSequence: Int = 0,
    )

    private class BackgroundRecord(
        val output: VmOutputWindow,
        val terminal: CompletableDeferred<VmExecutionResult>,
    ) {
        @Volatile
        var result: VmExecutionResult? = null

        @Volatile
        var error: VmProtocolException? = null
    }

    private data class StopResolution(
        val request: Request? = null,
        val completed: VmExecutionResult? = null,
        val shouldSignal: Boolean = false,
    )

    private enum class ConnectionState {
        NEW,
        CONNECTING,
        CONNECTED,
        CLOSED,
    }

    private enum class RequestState {
        ACTIVE,
        CANCELLING,
        EXITED,
        FAILED,
    }

    private companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_CLEANUP_WRITE_TIMEOUT_MILLIS = 1_000L
    }
}
