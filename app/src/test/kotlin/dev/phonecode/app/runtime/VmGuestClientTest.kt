package dev.phonecode.app.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmGuestClientTest {
    private val nonce = "12".repeat(32)

    @Test
    fun connectRequiresTheExactNonceIdentityAndCapabilities() = runBlocking {
        val fixture = Fixture()
        val guest = async(Dispatchers.IO) {
            assertEquals(helloFrame(nonce), fixture.readHostFrame())
            fixture.writeGuestFrame(readyFrame(nonce))
        }

        fixture.client.connect(nonce)
        guest.await()
        fixture.client.shutdown()
    }

    @Test
    fun connectRejectsWrongNonce() = runBlocking {
        val fixture = Fixture()
        val guest = async(Dispatchers.IO) {
            fixture.readHostFrame()
            fixture.writeGuestFrame(readyFrame("34".repeat(32)))
        }

        assertSuspendFails<VmProtocolException> { fixture.client.connect(nonce) }
        guest.await()
        Unit
    }

    @Test
    fun connectRejectsWrongCapabilities() = runBlocking {
        val fixture = Fixture()
        val guest = async(Dispatchers.IO) {
            fixture.readHostFrame()
            fixture.writeGuestFrame(
                readyFrame(nonce, capabilities = listOf("exec", "shutdown")),
                validate = false,
            )
        }

        assertSuspendFails<VmProtocolException> { fixture.client.connect(nonce) }
        guest.await()
        Unit
    }

    @Test
    fun startupTimesOutAndMalformedOrDuplicateReadyClosesTheTransport() = runBlocking {
        val timedOut = Fixture(startupTimeoutMillis = 50)
        assertSuspendFails<VmProtocolException> { timedOut.client.connect(nonce) }

        val malformed = Fixture()
        val malformedGuest = async(Dispatchers.IO) {
            fixtureReadHelloAndReply(malformed, readyFrame(nonce).without("agent"))
        }
        assertSuspendFails<VmProtocolException> { malformed.client.connect(nonce) }
        malformedGuest.await()

        val duplicate = Fixture()
        val duplicateGuest = async(Dispatchers.IO) {
            assertEquals(helloFrame(nonce), duplicate.readHostFrame())
            duplicate.writeGuestFrame(readyFrame(nonce))
            duplicate.writeGuestFrame(readyFrame(nonce))
        }
        duplicate.client.connect(nonce)
        duplicateGuest.await()
        assertSuspendFails<VmProtocolException> {
            duplicate.client.execute("true", timeoutMillis = 1_000)
        }
        Unit
    }

    @Test
    fun requestIdsIncreaseAndForegroundOutputIsOrderedAndBounded() = runBlocking {
        val fixture = connectedFixture()
        val ids = mutableListOf<Long>()
        val chunks = List(5) { index ->
            ByteArray(VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES) { (index + 1).toByte() }
        }
        val guest = async(Dispatchers.IO) {
            val first = fixture.readHostFrame()
            ids += first.long("id")
            chunks.forEachIndexed { sequence, bytes ->
                fixture.writeGuestFrame(outputFrame(first.long("id"), sequence, bytes))
            }
            fixture.writeGuestFrame(exitFrame(first.long("id"), status = 7, truncated = false))

            val second = fixture.readHostFrame()
            ids += second.long("id")
            fixture.writeGuestFrame(exitFrame(second.long("id"), status = 0, truncated = false))
        }

        val first = fixture.client.execute("printf lots", timeoutMillis = 1_000)
        val second = fixture.client.execute("true", timeoutMillis = 1_000)

        guest.await()
        assertEquals(listOf(1L, 2L), ids)
        assertEquals(7, first.status)
        assertTrue(first.truncated)
        assertEquals(VmGuestProtocol.MAX_RETAINED_OUTPUT_BYTES, first.output.size)
        assertArrayEquals(chunks.drop(1).reduce(ByteArray::plus), first.output)
        assertEquals(0, second.status)
        fixture.client.shutdown()
    }

    @Test
    fun outOfOrderOutputAndRemoteErrorsPropagate() = runBlocking {
        val outOfOrder = connectedFixture()
        val orderingGuest = async(Dispatchers.IO) {
            val exec = outOfOrder.readHostFrame()
            outOfOrder.writeGuestFrame(outputFrame(exec.long("id"), sequence = 1, bytes = byteArrayOf(1)))
        }
        assertSuspendFails<VmProtocolException> {
            outOfOrder.client.execute("printf bad", timeoutMillis = 1_000)
        }
        orderingGuest.await()

        val remoteError = connectedFixture()
        val errorGuest = async(Dispatchers.IO) {
            val exec = remoteError.readHostFrame()
            remoteError.writeGuestFrame(
                errorFrame(exec.long("id"), code = "EXEC_FAILED", message = "could not execute"),
            )
        }
        val error = assertSuspendFails<VmGuestRemoteException> {
            remoteError.client.execute("false", timeoutMillis = 1_000)
        }
        assertEquals("EXEC_FAILED", error.code)
        assertEquals("could not execute", error.message)
        errorGuest.await()
        remoteError.client.shutdown()
        Unit
    }

    @Test
    fun backgroundSessionSupportsOutputInputSignalsAndStop() = runBlocking {
        val fixture = connectedFixture()
        val outputWritten = CompletableDeferred<Unit>()
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            assertTrue(exec.boolean("background"))
            val id = exec.long("id")
            fixture.writeGuestFrame(startedFrame(id, pid = 4321))

            val stdin = fixture.readHostFrame()
            assertEquals(id, stdin.long("id"))
            assertEquals("stdin", stdin.string("type"))
            assertEquals("hello\n", decode(stdin.string("data_b64")).toString(Charsets.UTF_8))
            assertFalse(stdin.boolean("eof"))

            fixture.writeGuestFrame(outputFrame(id, sequence = 0, bytes = "ready\n".toByteArray()))
            outputWritten.complete(Unit)

            val interrupt = fixture.readHostFrame()
            assertEquals("signal", interrupt.string("type"))
            assertEquals("INT", interrupt.string("signal"))

            val stop = fixture.readHostFrame()
            assertEquals("signal", stop.string("type"))
            assertEquals("TERM", stop.string("signal"))
            fixture.writeGuestFrame(exitFrame(id, status = 143, truncated = false))
        }

        val session = fixture.client.start("cat", timeoutMillis = 1_000)
        assertEquals(4321, session.pid)
        fixture.client.input(session.id, "hello\n".toByteArray())
        select<Unit> {
            outputWritten.onAwait { }
            guest.onAwait { }
        }
        assertArrayEquals("ready\n".toByteArray(), fixture.client.output(session.id).output)
        fixture.client.signal(session.id, VmGuestSignal.INT)
        val stopped = fixture.client.stop(session.id)

        assertEquals(143, stopped.status)
        guest.await()
        fixture.client.shutdown()
    }

    @Test
    fun failedBackgroundStartDoesNotRetainAnUnreturnedSession() = runBlocking {
        val fixture = connectedFixture()
        var requestId = 0L
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            requestId = exec.long("id")
            fixture.writeGuestFrame(errorFrame(requestId, "START_FAILED", "no process"))
        }

        assertSuspendFails<VmGuestRemoteException> {
            fixture.client.start("missing", timeoutMillis = 1_000)
        }
        guest.await()
        val unknown = assertSuspendFails<VmProtocolException> {
            fixture.client.output(requestId)
        }
        fixture.client.shutdown()
        assertTrue(unknown.message.orEmpty().contains("unknown"))
    }

    @Test
    fun cancelledBackgroundStartDoesNotRetainAnUnreturnedSession() = runBlocking {
        val fixture = connectedFixture()
        val execSeen = CompletableDeferred<Unit>()
        var requestId = 0L
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            requestId = exec.long("id")
            execSeen.complete(Unit)
            val signal = fixture.readHostFrame()
            assertEquals(requestId, signal.long("id"))
            assertEquals("TERM", signal.string("signal"))
            fixture.writeGuestFrame(exitFrame(requestId, status = 143, truncated = false))
        }

        val starting = async {
            fixture.client.start("sleep 30", timeoutMillis = 30_000)
        }
        execSeen.await()
        starting.cancelAndJoin()
        guest.await()

        val unknown = assertSuspendFails<VmProtocolException> {
            fixture.client.output(requestId)
        }
        fixture.client.shutdown()
        assertTrue(unknown.message.orEmpty().contains("unknown"))
    }

    @Test
    fun stopSendsTermOnceAndRejectsInputAndSignalsWhileStopping() = runBlocking {
        val fixture = connectedFixture()
        val termSeen = CompletableDeferred<Unit>()
        val allowExit = CompletableDeferred<Unit>()
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            val id = exec.long("id")
            fixture.writeGuestFrame(startedFrame(id, pid = 77))

            val term = fixture.readHostFrame()
            assertEquals("signal", term.string("type"))
            assertEquals("TERM", term.string("signal"))
            termSeen.complete(Unit)
            allowExit.await()
            fixture.writeGuestFrame(exitFrame(id, status = 143, truncated = false))

            assertEquals(shutdownFrame(), fixture.readHostFrame())
        }

        val session = fixture.client.start("sleep 30", timeoutMillis = 30_000)
        val firstStop = async { fixture.client.stop(session.id) }
        termSeen.await()
        assertSuspendFails<VmProtocolException> {
            fixture.client.input(session.id, "late".toByteArray())
        }
        assertSuspendFails<VmProtocolException> {
            fixture.client.signal(session.id, VmGuestSignal.KILL)
        }
        val secondStop = async { fixture.client.stop(session.id) }
        allowExit.complete(Unit)
        assertEquals(143, firstStop.await().status)
        assertEquals(143, secondStop.await().status)
        fixture.client.shutdown()
        guest.await()
    }

    @Test
    fun cancellationSignalsTheRequestAndContinuesDrainingUntilExit() = runBlocking {
        val fixture = connectedFixture()
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            val signal = fixture.readHostFrame()
            assertEquals(exec.long("id"), signal.long("id"))
            assertEquals("TERM", signal.string("signal"))
            fixture.writeGuestFrame(outputFrame(exec.long("id"), sequence = 0, bytes = "late".toByteArray()))
            fixture.writeGuestFrame(exitFrame(exec.long("id"), status = 143, truncated = false))
        }

        val bytesBeforeExec = fixture.hostBytesWritten
        val execution = async {
            fixture.client.execute("sleep 30", timeoutMillis = 30_000)
        }
        withTimeout(5_000) {
            while (fixture.hostBytesWritten == bytesBeforeExec) {
                kotlinx.coroutines.yield()
            }
        }
        execution.cancelAndJoin()
        guest.await()

        val finalGuest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            fixture.writeGuestFrame(exitFrame(exec.long("id"), status = 0, truncated = false))
        }
        assertEquals(0, fixture.client.execute("true", timeoutMillis = 1_000).status)
        finalGuest.await()
        fixture.client.shutdown()
    }

    @Test
    fun cancellationCleanupSurvivesStateMutexContention() = runBlocking {
        val fixture = connectedFixture()
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            val signal = fixture.readHostFrame()
            assertEquals(exec.long("id"), signal.long("id"))
            assertEquals("TERM", signal.string("signal"))
            fixture.writeGuestFrame(exitFrame(exec.long("id"), status = 143, truncated = false))
        }
        val bytesBeforeExec = fixture.hostBytesWritten
        val execution = async {
            fixture.client.execute("sleep 30", timeoutMillis = 30_000)
        }
        withTimeout(5_000) {
            while (fixture.hostBytesWritten == bytesBeforeExec) {
                kotlinx.coroutines.yield()
            }
        }

        val mutex = fixture.client.stateMutex()
        mutex.lock()
        val completedDuringContention = try {
            execution.cancel()
            withTimeoutOrNull(200) {
                execution.join()
                Unit
            }
        } finally {
            mutex.unlock()
        }
        if (completedDuringContention != null) {
            fixture.client.shutdown()
            runCatching { guest.await() }
        }
        assertNull("cancellation cleanup skipped mutex contention", completedDuringContention)
        withTimeout(5_000) { execution.join() }
        guest.await()
        fixture.client.shutdown()
    }

    @Test
    fun eofFailsAllRequestsAndShutdownWritesTheLifecycleFrame() = runBlocking {
        val eofFixture = connectedFixture()
        val eofGuest = async(Dispatchers.IO) {
            eofFixture.readHostFrame()
            eofFixture.closeGuestOutput()
        }
        assertSuspendFails<VmProtocolException> {
            eofFixture.client.execute("sleep 1", timeoutMillis = 1_000)
        }
        eofGuest.await()

        val shutdownFixture = connectedFixture()
        val shutdownGuest = async(Dispatchers.IO) {
            assertEquals(shutdownFrame(), shutdownFixture.readHostFrame())
        }
        shutdownFixture.client.shutdown()
        shutdownFixture.client.shutdown()
        shutdownGuest.await()
        Unit
    }

    @Test
    fun shutdownInterruptsAndJoinsABlockedReader() = runBlocking {
        val input = InterruptAwareInputStream(VmGuestProtocol.encode(readyFrame(nonce)))
        val client = VmGuestClient(input, ByteArrayOutputStream())

        client.connect(nonce)
        withTimeout(5_000) {
            while (!input.isBlocking) {
                kotlinx.coroutines.yield()
            }
        }
        client.shutdown()

        assertTrue("reader was not interrupted before shutdown returned", input.wasInterrupted)
    }

    @Test
    fun cancellationInterruptsABlockedWriterBeforeCleanup() = runBlocking {
        val input = InterruptAwareInputStream(VmGuestProtocol.encode(readyFrame(nonce)))
        val output = InterruptAwareOutputStream()
        val client = VmGuestClient(input, output)
        client.connect(nonce)

        val execution = async {
            client.execute("sleep 30", timeoutMillis = 30_000)
        }
        withTimeout(5_000) {
            while (!output.isBlocking) {
                kotlinx.coroutines.yield()
            }
        }
        execution.cancel()
        val completedWhileBlocked = withTimeoutOrNull(500) {
            execution.join()
            true
        } == true
        output.release()
        withTimeout(5_000) { execution.join() }

        assertTrue("blocked protocol write ignored cancellation", completedWhileBlocked)
        assertTrue("blocked protocol writer was not interrupted", output.wasInterrupted)
        client.shutdown()
    }

    @Test
    fun cancellationBoundsABlockedCleanupTermWrite() = runBlocking {
        val input = InterruptAwareInputStream(VmGuestProtocol.encode(readyFrame(nonce)))
        val output = InterruptAwareOutputStream(blockOnWrite = 3)
        val client = VmGuestClient(
            input = input,
            output = output,
            cleanupWriteTimeoutMillis = 50,
        )
        client.connect(nonce)

        val starting = async {
            client.start("sleep 30", timeoutMillis = 30_000)
        }
        withTimeout(5_000) {
            while (output.writeCount < 2) {
                kotlinx.coroutines.yield()
            }
        }
        starting.cancel()
        val completedWhileCleanupWasBlocked = withTimeoutOrNull(500) {
            starting.join()
            true
        } == true
        output.release()
        withTimeout(5_000) { starting.join() }

        assertTrue("blocked cleanup TERM ignored its internal timeout", completedWhileCleanupWasBlocked)
        assertTrue("blocked cleanup TERM writer was not interrupted", output.wasInterrupted)
        assertSuspendFails<VmProtocolException> { client.output(1) }
        Unit
    }

    @Test
    fun stopIsWriteOrderedAgainstPrevalidatedInput() = runBlocking {
        val validated = CompletableDeferred<Unit>()
        val allowInputWrite = CompletableDeferred<Unit>()
        val fixture = connectedFixture(
            beforeBackgroundWrite = {
                validated.complete(Unit)
                allowInputWrite.await()
            },
        )
        val frameTypes = mutableListOf<String>()
        val guest = async(Dispatchers.IO) {
            val exec = fixture.readHostFrame()
            val id = exec.long("id")
            fixture.writeGuestFrame(startedFrame(id, pid = 88))
            repeat(2) {
                frameTypes += fixture.readHostFrame().string("type")
            }
            fixture.writeGuestFrame(exitFrame(id, status = 143, truncated = false))
        }

        val session = fixture.client.start("cat", timeoutMillis = 30_000)
        val bytesBeforeRace = fixture.hostBytesWritten
        val input = async {
            fixture.client.input(session.id, "before stop".toByteArray())
        }
        validated.await()
        val stopping = async { fixture.client.stop(session.id) }
        withTimeoutOrNull(200) {
            while (fixture.hostBytesWritten == bytesBeforeRace) {
                kotlinx.coroutines.yield()
            }
        }
        allowInputWrite.complete(Unit)

        input.await()
        stopping.await()
        guest.await()
        assertEquals(listOf("stdin", "signal"), frameTypes)
        fixture.client.shutdown()
    }

    private suspend fun connectedFixture(
        beforeBackgroundWrite: suspend () -> Unit = {},
    ): Fixture = coroutineScope {
        val fixture = Fixture(beforeBackgroundWrite = beforeBackgroundWrite)
        val guest = async(Dispatchers.IO) {
            assertEquals(helloFrame(nonce), fixture.readHostFrame())
            fixture.writeGuestFrame(readyFrame(nonce))
        }
        fixture.client.connect(nonce)
        guest.await()
        fixture
    }

    private fun fixtureReadHelloAndReply(fixture: Fixture, response: JsonObject) {
        assertEquals(helloFrame(nonce), fixture.readHostFrame())
        fixture.writeGuestFrame(response, validate = false)
    }

    private class Fixture(
        startupTimeoutMillis: Long = 1_000,
        beforeBackgroundWrite: suspend () -> Unit = {},
    ) {
        private val clientInput = PipedInputStream(128 * 1024)
        private val guestOutput = PipedOutputStream(clientInput)
        private val guestInput = PipedInputStream(128 * 1024)
        private val clientOutput = CountingOutputStream(PipedOutputStream(guestInput))
        val client = VmGuestClient(
            input = clientInput,
            output = clientOutput,
            startupTimeoutMillis = startupTimeoutMillis,
            beforeBackgroundWrite = beforeBackgroundWrite,
        )
        val hostBytesWritten: Long
            get() = clientOutput.count

        fun readHostFrame(): JsonObject = requireNotNull(VmGuestProtocol.read(guestInput))

        fun writeGuestFrame(frame: JsonObject, validate: Boolean = true) {
            val bytes = if (validate) {
                VmGuestProtocol.encode(frame)
            } else {
                VmGuestProtocol.encodeCanonicalUnchecked(frame)
            }
            guestOutput.write(bytes)
            guestOutput.flush()
        }

        fun closeGuestOutput() {
            guestOutput.close()
        }
    }

    private fun VmGuestClient.stateMutex(): Mutex {
        val field = VmGuestClient::class.java.getDeclaredField("stateMutex")
        field.isAccessible = true
        return field.get(this) as Mutex
    }

    private class CountingOutputStream(
        private val delegate: PipedOutputStream,
    ) : java.io.OutputStream() {
        @Volatile
        var count: Long = 0
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private class InterruptAwareInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private val forever = CountDownLatch(1)

        @Volatile
        var isBlocking = false
            private set

        @Volatile
        var wasInterrupted = false
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) return value
            isBlocking = true
            try {
                forever.await()
            } catch (_: InterruptedException) {
                wasInterrupted = true
                throw InterruptedIOException("reader interrupted")
            }
            throw IOException("unreachable")
        }

        override fun close() = Unit
    }

    private class InterruptAwareOutputStream(
        private val blockOnWrite: Int = 2,
    ) : java.io.OutputStream() {
        private val release = CountDownLatch(1)
        @Volatile
        var writeCount = 0
            private set

        @Volatile
        var isBlocking = false
            private set

        @Volatile
        var wasInterrupted = false
            private set

        override fun write(value: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writeCount++
            if (writeCount != blockOnWrite) return
            isBlocking = true
            try {
                release.await()
            } catch (_: InterruptedException) {
                wasInterrupted = true
                throw InterruptedIOException("writer interrupted")
            } finally {
                isBlocking = false
            }
        }

        fun release() {
            release.countDown()
        }
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit,
    ): T {
        val result = runCatching { block() }
        val error = result.exceptionOrNull()
            ?: throw AssertionError("Expected ${T::class.java.simpleName}, but completed successfully")
        if (error !is T) {
            throw AssertionError(
                "Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}",
                error,
            )
        }
        return error
    }

    private fun JsonObject.without(name: String): JsonObject = JsonObject(this - name)

    private fun JsonObject.string(name: String): String = requireNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = string(name).toLong()

    private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()

    private fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

    private fun helloFrame(value: String) = buildJsonObject {
        put("id", 0)
        put("nonce", value)
        put("type", "hello")
        put("v", 1)
    }

    private fun readyFrame(
        value: String,
        capabilities: List<String> = VmGuestProtocol.REQUIRED_CAPABILITIES,
    ) = buildJsonObject {
        put("agent", "phonecode-guestd")
        put("capabilities", buildJsonArray {
            capabilities.forEach { add(JsonPrimitive(it)) }
        })
        put("id", 0)
        put("nonce", value)
        put("type", "ready")
        put("v", 1)
    }

    private fun outputFrame(id: Long, sequence: Int, bytes: ByteArray) = buildJsonObject {
        put("data_b64", Base64.getEncoder().encodeToString(bytes))
        put("id", id)
        put("seq", sequence)
        put("type", "output")
        put("v", 1)
    }

    private fun startedFrame(id: Long, pid: Int) = buildJsonObject {
        put("id", id)
        put("pid", pid)
        put("type", "started")
        put("v", 1)
    }

    private fun exitFrame(id: Long, status: Int, truncated: Boolean) = buildJsonObject {
        put("id", id)
        put("status", status)
        put("truncated", truncated)
        put("type", "exit")
        put("v", 1)
    }

    private fun errorFrame(id: Long, code: String, message: String) = buildJsonObject {
        put("code", code)
        put("id", id)
        put("message", message)
        put("type", "error")
        put("v", 1)
    }

    private fun shutdownFrame() = buildJsonObject {
        put("id", 0)
        put("type", "shutdown")
        put("v", 1)
    }
}
