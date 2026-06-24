package dev.phonecode.tools.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun config() = McpServerConfig(url = server.url("/mcp").toString())

    @Test fun connectListsToolsNamespacedAndPropagatesSessionId() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-1")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202)) // notifications/initialized
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_weather","description":"Get weather","inputSchema":{"type":"object"}}]}}"""),
        )

        val tools = McpClient("My Weather!", config(), http).connect()

        assertEquals(1, tools.size)
        assertEquals("My_Weather__get_weather", tools[0].name)
        assertEquals("Get weather", tools[0].description)

        // initialize is first (no session captured yet); subsequent calls must echo the captured session id.
        assertNull(server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("sess-1", server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("sess-1", server.takeRequest().getHeader("Mcp-Session-Id"))
    }

    @Test fun callToolParsesSseAndTakesTheResultEvent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"sunny, 22C\"}]}}\n\n",
                ),
        )
        val out = McpClient("weather", config(), http).callTool("get_weather", JsonObject(emptyMap()))
        assertEquals("sunny, 22C", out)
    }

    @Test fun callToolSurfacesServerError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"boom"}]}}"""),
        )
        val out = McpClient("weather", config(), http).callTool("get_weather", JsonObject(emptyMap()))
        assertTrue(out.startsWith("ERROR:"))
        assertTrue(out.contains("boom"))
    }
}
