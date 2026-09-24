package dev.phonecode.app.runtime

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.provider.preset.BuiltInPresets
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Streams Anthropic Messages SSE through the packaged native runtime and inspects each raw HTTP request. */
@RunWith(AndroidJUnit4::class)
class MisulAnthropicTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun messagesStreamRunsToolLoopAndReplaysSignedThinking() = runBlocking {
        val root = root("tool-loop")
        val first = sse(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-test","usage":{"input_tokens":12,"cache_read_input_tokens":0,"output_tokens":1}}}""",
            """{"type":"ping"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Read the file."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Reading."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\": "}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":30}}""",
            """{"type":"message_stop"}""",
        )
        val second = sse(
            """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","content":[],"model":"claude-test","usage":{"input_tokens":40,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"The file says alpha."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":6}}""",
            """{"type":"message_stop"}""",
        )
        val (result, requests) = prompt(spec(root, reasoning = "high"), listOf(Reply(200, first), Reply(200, second)))
        Log.i(TAG, "[tool-loop] status=${result.status} content=${result.content} failure=${result.providerFailure}")
        assertEquals("completed", result.status)
        assertTrue(result.content.contains("The file says alpha."))
        assertEquals(2, requests.size)
        for (request in requests) {
            assertEquals("POST /v1/messages HTTP/1.1", request.line)
            assertEquals(KEY, request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            assertNull(request.headers["authorization"])
        }
        val body = JSONObject(requests[0].body)
        assertEquals("claude-test", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(256, body.getInt("max_tokens"))
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
        assertEquals("text", body.getJSONArray("system").getJSONObject(0).getString("type"))
        val tools = body.getJSONArray("tools")
        val readFile = (0 until tools.length()).map(tools::getJSONObject).single { it.getString("name") == "read_file" }
        assertEquals("object", readFile.getJSONObject("input_schema").getString("type"))
        assertFalse(readFile.has("function"))

        val replay = JSONObject(requests[1].body).getJSONArray("messages")
        val assistant = replay.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val blocks = assistant.getJSONArray("content")
        assertEquals("thinking", blocks.getJSONObject(0).getString("type"))
        assertEquals("sig-abc", blocks.getJSONObject(0).getString("signature"))
        assertEquals("Read the file.", blocks.getJSONObject(0).getString("thinking"))
        val toolUse = (0 until blocks.length()).map(blocks::getJSONObject).single { it.getString("type") == "tool_use" }
        assertEquals("toolu_01", toolUse.getString("id"))
        assertEquals("a.txt", toolUse.getJSONObject("input").getString("path"))
        val results = replay.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        val toolResult = results.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("toolu_01", toolResult.getString("tool_use_id"))
        assertEquals("alpha\n", toolResult.getString("content"))
    }

    @Test
    fun anthropicErrorsAreStructuredAndRedacted() = runBlocking {
        val root = root("error")
        val error = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $KEY"},"request_id":"req_1"}"""
        val (result, _) = prompt(spec(root), listOf(Reply(401, error)))
        val failure = result.providerFailure!!
        Log.i(TAG, "[error] status=${result.status} failure=$failure")
        assertEquals("authentication", failure.category)
        assertEquals(401, failure.httpStatus)
        assertEquals("authentication_error", failure.providerType)
        assertFalse(failure.message.contains(KEY))
    }

    private data class Reply(val status: Int, val body: String)
    private data class Request(val line: String, val headers: Map<String, String>, val body: String)

    private suspend fun prompt(spec: MisulRuntimeSpec, replies: List<Reply>): Pair<MisulPromptResult, List<Request>> =
        ServerSocket(0, replies.size, InetAddress.getByName("127.0.0.1")).use { server ->
            val requests = CopyOnWriteArrayList<Request>()
            val responder = Executors.newSingleThreadExecutor()
            responder.submit {
                replies.forEach { reply ->
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val line = input.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = input.readLine() ?: break
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toInt() ?: 0
                        val body = CharArray(length)
                        var read = 0
                        while (read < length) read += input.read(body, read, length - read).takeIf { it > 0 } ?: break
                        requests += Request(line, headers, String(body, 0, read))
                        val output = socket.getOutputStream()
                        if (reply.status != 200) {
                            val bytes = reply.body.encodeToByteArray()
                            output.write("HTTP/1.1 ${reply.status} Error\r\nContent-Type: application/json\r\nrequest-id: req_1\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            output.write(bytes)
                        } else {
                            // Chunked, one flush per SSE event, like the real streaming endpoint.
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            reply.body.split("\n\n").filter { it.isNotEmpty() }.forEach { event ->
                                val bytes = "$event\n\n".encodeToByteArray()
                                output.write("${bytes.size.toString(16)}\r\n".encodeToByteArray())
                                output.write(bytes)
                                output.write("\r\n".encodeToByteArray())
                                output.flush()
                            }
                            output.write("0\r\n\r\n".encodeToByteArray())
                        }
                        output.flush()
                    }
                }
            }
            val controller = MisulRuntimeController()
            try {
                // The preset base has no /v1 segment; the native runtime adds /v1/messages.
                val endpoint = "http://127.0.0.1:${server.localPort}"
                val result = controller.prompt(spec.copy(provider = spec.provider.copy(endpoint = endpoint)), "session-${spec.workspaceRoot.parentFile!!.name}", "Read a.txt", {})
                result to requests.toList()
            } finally {
                controller.close()
                responder.shutdownNow()
                responder.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

    private fun sse(vararg events: String): String = events.joinToString("") { data ->
        "event: ${JSONObject(data).getString("type")}\ndata: $data\n\n"
    }

    private fun root(name: String) = File(context.cacheDir, "misul-anthropic-$name").apply {
        deleteRecursively()
        resolve("workspace").mkdirs()
        resolve("workspace/a.txt").writeText("alpha\n")
    }

    private fun spec(root: File, reasoning: String? = null) = MisulRuntimeSpec(
        workspaceRoot = root.resolve("workspace"),
        stateRoot = root.resolve("state"),
        systemPrompt = "PhoneCode Anthropic test",
        model = MisulModel("claude-test", "Claude test", "anthropic", 200_000, 256, reasoning = reasoning),
        provider = MisulProvider("anthropic", "http://127.0.0.1:1", KEY, "anthropic_messages", BuiltInPresets.anthropic.extraHeaders),
        allowMutatingTools = false,
    )

    private companion object {
        const val TAG = "MisulAnthropicTest"
        const val KEY = "sk-ant-api03-synthetic-test-key_000"
    }
}
