package dev.phonecode.app.runtime

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.agent.ExtensionConfigReadTool
import dev.phonecode.app.agent.ExtensionConfigWriteTool
import dev.phonecode.app.data.AppSettingsStore
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Replays provider tool-call streams through the packaged native runtime and inspects each request body. */
@RunWith(AndroidJUnit4::class)
class MisulToolCallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun commonProviderToolCallShapesExecute() = runBlocking {
        val shapes = listOf(
            Triple("empty_args", call("c1", "list_files", ""), "stop") to "a.txt",
            Triple("stop_finish", call("c1", "list_files", """{"path":"."}"""), "stop") to "a.txt",
            Triple("absolute_path", call("c1", "read_file", """{"path":"WORKSPACE/a.txt"}"""), "tool_calls") to "alpha",
            Triple("mount_path", call("c1", "read_file", """{"path":"/workspace/a.txt"}"""), "tool_calls") to "alpha",
            Triple("extra_field", call("c1", "read_file", """{"path":"a.txt","explanation":"read it"}"""), "tool_calls") to "alpha",
        )
        for ((shape, expected) in shapes) {
            val (name, toolCall, finish) = shape
            val root = root(name)
            val workspace = root.resolve("workspace").absolutePath
            val stream = toolCallStream(listOf(toolCall.replace("WORKSPACE", workspace)), finish)
            val (result, requests) = prompt(spec(root, 0), listOf(stream, textStream("done")))
            Log.i(TAG, "[$name] status=${result.status} provider=${result.providerFailure} results=${toolResults(requests)}")
            assertEquals(name, "completed", result.status)
            assertEquals(name, expected, toolResults(requests).single().trim())
        }
    }

    @Test
    fun hostToolsAreAdvertisedAndIndependentCallsRunConcurrently() = runBlocking {
        val meeting = CountDownLatch(2)
        val meet = tool("meet") { args ->
            meeting.countDown()
            val met = meeting.await(10, TimeUnit.SECONDS)
            ToolResult(if (met) "met ${args["tag"]}" else "timed out alone", isError = !met)
        }
        val root = root("host-parallel")
        val stream = toolCallStream(listOf(
            call("c1", "meet", """{"tag":"first"}"""),
            call("c2", "meet", """{"tag":"second"}"""),
            call("c3", "read_file", """{"path":"a.txt"}"""),
        ), "tool_calls")
        val (result, requests) = prompt(
            spec(root, 0).copy(hostTools = listOf(meet), toolContext = toolContext(root), disabledTools = setOf("write_file")),
            listOf(stream, textStream("done")),
        )
        val tools = advertisedTools(requests.first())
        Log.i(TAG, "[host-parallel] tools=$tools results=${toolResults(requests)}")
        assertEquals("completed", result.status)
        assertTrue("meet" in tools && "read_file" in tools && "write_file" !in tools)
        assertEquals(listOf("met \"first\"", "met \"second\"", "alpha\n"), toolResults(requests))
    }

    @Test
    fun mutatingHostToolWaitsForApproval() = runBlocking {
        val root = root("host-approval")
        val touch = tool("touch", mutating = true) {
            root.resolve("workspace/touched.txt").writeText("yes")
            ToolResult("touched")
        }
        val stream = toolCallStream(listOf(call("c1", "touch", "{}")), "tool_calls")
        val approvals = CopyOnWriteArrayList<String>()
        val decisions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MisulRuntimeController()
        try {
            val (result, requests) = prompt(
                spec(root, 0).copy(hostTools = listOf(touch), toolContext = toolContext(root)),
                listOf(stream, textStream("done")),
                controller,
            ) { event ->
                if (event is MisulRuntimeEvent.ApprovalRequested) {
                    approvals += event.name
                    assertTrue(!root.resolve("workspace/touched.txt").exists())
                    decisions.launch { controller.respondToApproval(event.id, true) }
                }
            }
            assertEquals("completed", result.status)
            assertEquals(listOf("touch"), approvals.toList())
            assertEquals(listOf("touched"), toolResults(requests))
            assertEquals("yes", root.resolve("workspace/touched.txt").readText())
        } finally {
            decisions.cancel()
        }
    }

    @Test
    fun agentManagesSkillsAndToolSettingsThroughTheBridge() = runBlocking {
        val root = root("host-config")
        val repository = McpSkillRepository(root.resolve("config"))
        val settings = AppSettingsStore(root.resolve("settings.json"))
        val workspace = root.resolve("workspace")
        val stream = toolCallStream(listOf(
            call("c1", "extension_write", JSONObject().put("action", "write_skill").put("scope", "global").put("name", "review")
                .put("content", "---\nname: review\ndescription: Review diffs\n---\nCheck tests.").toString()),
            call("c2", "extension_write", """{"action":"set_tool","name":"webfetch","enabled":false}"""),
            call("c3", "extension_write", """{"action":"set_tool","name":"extension_write","enabled":false}"""),
            call("c4", "extension_read", """{"action":"inventory"}"""),
        ), "tool_calls")
        val (result, requests) = prompt(
            spec(root, 0).copy(
                allowMutatingTools = true,
                hostTools = listOf(ExtensionConfigReadTool(repository, settings) { workspace }, ExtensionConfigWriteTool(repository, settings) { workspace }),
                toolContext = toolContext(root),
            ),
            listOf(stream, textStream("done")),
        )
        val results = toolResults(requests)
        Log.i(TAG, "[host-config] results=$results")
        assertEquals("completed", result.status)
        assertEquals("Skill file saved", results[0])
        assertEquals(setOf("webfetch"), settings.load().disabledTools)
        assertTrue(results[2].contains("stays enabled"))
        assertTrue(results[3].contains("global/review"))
        assertTrue(repository.scanSkills(workspace).items.any { it.name == "review" })
    }

    private suspend fun prompt(
        spec: MisulRuntimeSpec,
        replies: List<String>,
        controller: MisulRuntimeController = MisulRuntimeController(),
        onEvent: (MisulRuntimeEvent) -> Unit = {},
    ): Pair<MisulPromptResult, List<String>> = ServerSocket(0, replies.size, InetAddress.getByName("127.0.0.1")).use { server ->
        val requests = CopyOnWriteArrayList<String>()
        val responder = Executors.newSingleThreadExecutor()
        responder.submit {
            replies.forEach { reply ->
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
                        if (line.isEmpty()) break
                    }
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) read += input.read(body, read, contentLength - read).takeIf { it > 0 } ?: break
                    requests += String(body, 0, read)
                    val bytes = reply.encodeToByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    output.write(bytes)
                    output.flush()
                }
            }
        }
        try {
            val endpoint = "http://127.0.0.1:${server.localPort}/v1"
            val result = controller.prompt(spec.copy(provider = spec.provider.copy(endpoint = endpoint)), "session-${spec.workspaceRoot.parentFile!!.name}", "go", onEvent)
            result to requests.toList()
        } finally {
            controller.close()
            responder.shutdownNow()
            responder.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun root(name: String) = File(context.cacheDir, "misul-tool-$name").apply {
        deleteRecursively()
        resolve("workspace").mkdirs()
        resolve("workspace/a.txt").writeText("alpha\n")
    }

    private fun toolContext(root: File) = object : ToolContext {
        override val workspacePath = root.resolve("workspace").absolutePath
        override suspend fun requestPermission(tool: String, summary: String) = false
    }

    private fun tool(name: String, mutating: Boolean = false, run: (JsonObject) -> ToolResult) = object : Tool {
        override val name = name
        override val description = "Test tool $name"
        override val mutating = mutating
        override val parameters = buildJsonObject { put("type", JsonPrimitive("object")) }
        override suspend fun execute(args: JsonObject, context: ToolContext) = run(args)
    }

    private fun call(id: String, name: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments)).toString()

    private fun toolCallStream(calls: List<String>, finish: String): String {
        val array = JSONArray()
        calls.forEachIndexed { index, call -> array.put(JSONObject(call).put("index", index)) }
        val delta = JSONObject().put("role", "assistant").put("tool_calls", array)
        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish)))
        return "data: $chunk\n\ndata: [DONE]\n\n"
    }

    private fun textStream(text: String): String =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun advertisedTools(body: String): List<String> = JSONObject(body).getJSONArray("tools").let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun toolResults(requests: List<String>): List<String> {
        val messages = JSONObject(requests.last()).getJSONArray("messages")
        return (0 until messages.length()).map(messages::getJSONObject)
            .filter { it.optString("role") == "tool" }
            .map { it.getString("content") }
    }

    private fun spec(root: File, port: Int) = MisulRuntimeSpec(
        workspaceRoot = root.resolve("workspace"),
        stateRoot = root.resolve("state"),
        systemPrompt = "PhoneCode tool call test",
        model = MisulModel("phonecode-test-model", "PhoneCode test model", "phonecode-test-provider", 4096, 256),
        provider = MisulProvider("phonecode-test-provider", "http://127.0.0.1:$port/v1", "fixture-token", "openai_chat"),
        allowMutatingTools = false,
    )

    private companion object {
        const val TAG = "MisulToolCallTest"
    }
}
