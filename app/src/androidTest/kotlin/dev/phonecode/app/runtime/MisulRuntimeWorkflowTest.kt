package dev.phonecode.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.data.PersistedMessage
import dev.phonecode.app.data.PersistedPart
import dev.phonecode.app.data.PersistedRole
import dev.phonecode.app.data.PersistedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MisulRuntimeWorkflowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun nativeRuntimeStreamsARealProviderResponseThroughJni() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val request = StringBuilder()
            val responder = Executors.newSingleThreadExecutor()
            val responseBody = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"index":0,"delta":{"content":"native alpha"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                data: [DONE]

            """.trimIndent().plus("\n\n").encodeToByteArray()
            responder.submit {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        request.append(line).append('\n')
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                        if (line.isEmpty()) break
                    }
                    repeat(contentLength) { request.append(input.read().toChar()) }
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: text/event-stream\r\n")
                        output.write("Content-Length: ${responseBody.size}\r\n")
                        output.write("Connection: close\r\n\r\n")
                        output.flush()
                        socket.getOutputStream().write(responseBody)
                        socket.getOutputStream().flush()
                    }
                }
            }

            val root = File(context.cacheDir, "misul-runtime-workflow").apply {
                deleteRecursively()
                mkdirs()
            }
            val controller = MisulRuntimeController()
            try {
                val events = mutableListOf<MisulRuntimeEvent>()
                val runtimeSpec = spec(root, server.localPort)
                val legacy = PersistedSession(
                    id = "session-phonecode-workflow",
                    title = "Restored chat",
                    updatedAt = 1,
                    messages = listOf(
                        PersistedMessage(PersistedRole.USER, listOf(PersistedPart.Text("legacy question"))),
                        PersistedMessage(PersistedRole.ASSISTANT, listOf(PersistedPart.Text("legacy answer"))),
                    ),
                ).toMisulImportSession("phonecode-test-provider", "phonecode-test-model", "openai_chat")
                assertEquals(1, controller.importSessions(runtimeSpec, listOf(legacy)))
                assertEquals(0, controller.importSessions(runtimeSpec, listOf(legacy)))
                val result = controller.prompt(
                    spec = runtimeSpec,
                    sessionId = "session-phonecode-workflow",
                    prompt = "hello from Android",
                    onEvent = events::add,
                )
                assertEquals("completed", result.status)
                assertEquals("native alpha", result.content)
                assertTrue(events.any { it == MisulRuntimeEvent.Text("native alpha") })
                assertTrue(request.startsWith("POST /v1/chat/completions HTTP/1.1"))
                assertTrue(request.contains("Authorization: Bearer fixture-token", ignoreCase = true))
                assertTrue("\"model\":\"phonecode-test-model\"" in request)
                assertTrue("legacy question" in request)
                assertTrue("legacy answer" in request)
            } finally {
                controller.close()
                responder.shutdownNow()
                responder.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun nativeRuntimeWaitsForPhoneApprovalBeforeWriting() = runBlocking {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = Executors.newSingleThreadExecutor()
            val replies = listOf(
                """
                    data: {"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-write","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"approved.txt\",\"content\":\"approved\\n\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                """.trimIndent().plus("\n\n"),
                """
                    data: {"choices":[{"delta":{"role":"assistant","content":"write approved"},"finish_reason":"stop"}]}

                    data: [DONE]

                """.trimIndent().plus("\n\n"),
            )
            responder.submit {
                replies.forEach { reply ->
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                            if (line.isEmpty()) break
                        }
                        repeat(contentLength) { input.read() }
                        val body = reply.encodeToByteArray()
                        socket.getOutputStream().bufferedWriter().use { output ->
                            output.write("HTTP/1.1 200 OK\r\n")
                            output.write("Content-Type: text/event-stream\r\n")
                            output.write("Content-Length: ${body.size}\r\n")
                            output.write("Connection: close\r\n\r\n")
                            output.flush()
                            socket.getOutputStream().write(body)
                            socket.getOutputStream().flush()
                        }
                    }
                }
            }

            val root = File(context.cacheDir, "misul-runtime-approval").apply {
                deleteRecursively()
                mkdirs()
            }
            val controller = MisulRuntimeController()
            val decisions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val events = mutableListOf<MisulRuntimeEvent>()
                val result = controller.prompt(
                    spec = spec(root, server.localPort),
                    sessionId = "session-phonecode-approval",
                    prompt = "write approved.txt",
                ) { event ->
                    events += event
                    if (event is MisulRuntimeEvent.ApprovalRequested) {
                        decisions.launch { controller.respondToApproval(event.id, true) }
                    }
                }

                assertEquals("completed", result.status)
                assertEquals("write approved", result.content)
                assertEquals("approved\n", root.resolve("workspace/approved.txt").readText())
                assertTrue(events.any { it is MisulRuntimeEvent.ApprovalRequested && it.name == "write_file" })
                assertTrue(events.any { it is MisulRuntimeEvent.ToolFinished && !it.isError })
            } finally {
                decisions.cancel()
                controller.close()
                responder.shutdownNow()
                responder.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun cancelWhileNativeApprovalIsPendingDoesNotWrite() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = Executors.newSingleThreadExecutor()
            val reply = """
                data: {"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-cancel","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"must-not-exist.txt\",\"content\":\"no\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent().plus("\n\n").encodeToByteArray()
            responder.submit {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                        if (line.isEmpty()) break
                    }
                    repeat(contentLength) { input.read() }
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: text/event-stream\r\n")
                        output.write("Content-Length: ${reply.size}\r\n")
                        output.write("Connection: close\r\n\r\n")
                        output.flush()
                        socket.getOutputStream().write(reply)
                        socket.getOutputStream().flush()
                    }
                }
            }

            val root = File(context.cacheDir, "misul-runtime-approval-cancel").apply {
                deleteRecursively()
                mkdirs()
            }
            val controller = MisulRuntimeController()
            val decisions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val result = controller.prompt(
                    spec = spec(root, server.localPort),
                    sessionId = "session-phonecode-approval-cancel",
                    prompt = "try to write",
                ) { event ->
                    if (event is MisulRuntimeEvent.ApprovalRequested) {
                        decisions.launch { controller.abort() }
                    }
                }

                assertEquals("canceled", result.status)
                assertTrue(!root.resolve("workspace/must-not-exist.txt").exists())
            } finally {
                decisions.cancel()
                controller.close()
                responder.shutdownNow()
                responder.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun spec(root: File, port: Int) = MisulRuntimeSpec(
        workspaceRoot = root.resolve("workspace"),
        stateRoot = root.resolve("state"),
        systemPrompt = "PhoneCode native workflow test",
        model = MisulModel(
            id = "phonecode-test-model",
            name = "PhoneCode test model",
            provider = "phonecode-test-provider",
            contextWindow = 4096,
            outputLimit = 256,
        ),
        provider = MisulProvider(
            id = "phonecode-test-provider",
            endpoint = "http://127.0.0.1:$port/v1",
            credential = "fixture-token",
            dialect = "openai_chat",
        ),
    )
}
