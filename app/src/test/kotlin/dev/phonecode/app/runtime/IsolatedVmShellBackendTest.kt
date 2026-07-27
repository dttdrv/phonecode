package dev.phonecode.app.runtime

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedVmShellBackendTest {
    @Test
    fun missingProjectWorkspaceTransportFailsClosedWithoutStartingVm() = runBlocking {
        var starts = 0
        val backend = IsolatedVmShellBackend(
            runtimeFactory = {
                starts++
                VmShellRuntime(FakeClient()) {}
            },
            acquireForegroundLease = {},
            releaseForegroundLease = {},
            availabilityBlocker = IsolatedVmShellBackend.WORKSPACE_BRIDGE_UNAVAILABLE,
        )

        val status = backend.status("/project")
        val result = backend.execute("pwd", "/project", 30)

        assertFalse(status.available)
        assertEquals(
            "Isolated VM project workspace transport is not available in this release build.",
            status.detail,
        )
        assertTrue(result.isError)
        assertEquals(status.detail, result.output)
        assertEquals(0, starts)
    }

    @Test
    fun foregroundExecutionUsesGuestAndBalancesForegroundLease() = runBlocking {
        val client = FakeClient().apply {
            executeResult = VmExecutionResult(1, 0, "from guest".toByteArray(), truncated = false)
        }
        val leases = mutableListOf<String>()
        val backend = backend(
            client = client,
            acquire = { leases += "acquire:$it" },
            release = { leases += "release:$it" },
        )

        val result = backend.execute("pwd", "/project", 30)

        assertEquals(listOf("pwd" to 30_000), client.executed)
        assertEquals("from guest", result.output)
        assertFalse(result.isError)
        assertEquals(2, leases.size)
        assertTrue(leases[0].startsWith("acquire:vm-exec:"))
        assertEquals(leases[0].removePrefix("acquire:"), leases[1].removePrefix("release:"))
        assertTrue(client.shutdowns > 0)
    }

    @Test
    fun backgroundSessionsEnforceWorkspaceOwnershipAndForwardInput() = runBlocking {
        val client = FakeClient()
        val released = CopyOnWriteArrayList<String>()
        val backend = backend(client = client, release = released::add)

        val started = backend.start("cat", "/project-a")
        val sessionId = requireNotNull(Regex("vm-\\d+").find(started.output)?.value)

        assertTrue(backend.output(sessionId, "/project-b").isError)
        assertTrue(backend.input(sessionId, "hello", true, "/project-b").isError)
        assertFalse(backend.input(sessionId, "hello", true, "/project-a").isError)
        assertEquals("hello\n", client.inputs.single().second.toString(Charsets.UTF_8))

        val stopped = backend.stop(sessionId, "/project-a")
        assertFalse(stopped.isError)
        assertEquals(143, client.stopResult.status)
        client.backgroundExit.complete(client.stopResult)
        assertTrue(released.isNotEmpty())
    }

    @Test
    fun cancellationIsNotConvertedIntoAToolError() = runBlocking {
        val client = FakeClient().apply { executeFailure = kotlinx.coroutines.CancellationException("cancel") }
        val backend = backend(client)

        val error = runCatching { backend.execute("sleep 30", "/project", 30) }.exceptionOrNull()

        assertTrue(error is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun completedBackgroundSessionHistoryIsBounded() = runBlocking {
        val client = FakeClient().apply { autoCompleteBackgrounds = true }
        val backend = backend(client)

        repeat(30) { backend.start("printf $it", "/project") }
        withTimeout(2_000) {
            while (backend.list("/project").output.lineSequence().count() > 24) delay(10)
        }

        val listed = backend.list("/project").output.lineSequence().toList()
        assertEquals(24, listed.size)
        assertFalse(listed.any { it.contains("printf 0") })
        assertTrue(listed.any { it.contains("printf 29") })
    }

    private fun backend(
        client: FakeClient,
        acquire: (String) -> Unit = {},
        release: (String) -> Unit = {},
    ) = IsolatedVmShellBackend(
        runtimeFactory = { VmShellRuntime(client) {} },
        acquireForegroundLease = acquire,
        releaseForegroundLease = release,
    )

    private class FakeClient : VmShellClient {
        var executeResult = VmExecutionResult(1, 0, ByteArray(0), false)
        var executeFailure: Throwable? = null
        val executed = mutableListOf<Pair<String, Int>>()
        val inputs = mutableListOf<Pair<Long, ByteArray>>()
        val backgroundExit = CompletableDeferred<VmExecutionResult>()
        var autoCompleteBackgrounds = false
        val stopResult = VmExecutionResult(7, 143, "stopped".toByteArray(), false)
        var shutdowns = 0

        override suspend fun execute(command: String, timeoutMillis: Int): VmExecutionResult {
            executed += command to timeoutMillis
            executeFailure?.let { throw it }
            return executeResult
        }

        override suspend fun start(command: String, timeoutMillis: Int) =
            VmBackgroundSession(7, 700)

        override fun output(id: Long) = VmSessionOutput(ByteArray(0), false, true, null)

        override suspend fun input(id: Long, bytes: ByteArray, eof: Boolean) {
            inputs += id to bytes
        }

        override suspend fun signal(id: Long, signal: VmGuestSignal) = Unit

        override suspend fun stop(id: Long) = stopResult

        override suspend fun awaitBackground(id: Long) =
            if (autoCompleteBackgrounds) VmExecutionResult(id, 0, "done".toByteArray(), false)
            else backgroundExit.await()

        override suspend fun shutdown() {
            shutdowns++
        }
    }
}
