package dev.phonecode.tools.web

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebFetchToolTest {

    private lateinit var server: MockWebServer
    private val tool = WebFetchTool(OkHttpClient())

    private object Ctx : ToolContext {
        override val workspacePath = "/tmp"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun urlArgs(path: String): JsonObject = buildJsonObject { put("url", server.url(path).toString()) }

    @Test fun stripsHtmlToReadableText() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>t</title><style>.x{}</style></head><body><h1>Hello</h1><p>World &amp; more</p><script>bad()</script></body></html>"),
        )
        val result = tool.execute(urlArgs("/page"), Ctx)
        assertFalse(result.isError)
        assertTrue(result.output.contains("Hello"))
        assertTrue(result.output.contains("World & more"))
        assertFalse(result.output.contains("bad()")) // script stripped
        assertFalse(result.output.contains("<h1>")) // tags stripped
    }

    @Test fun returnsErrorOnNon2xx() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val result = tool.execute(urlArgs("/missing"), Ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("404"))
    }

    @Test fun rejectsNonHttpScheme() = runBlocking {
        val result = tool.execute(buildJsonObject { put("url", "ftp://example.com/x") }, Ctx)
        assertTrue(result.isError)
    }

    @Test fun missingUrlIsError() = runBlocking {
        assertTrue(tool.execute(JsonObject(emptyMap()), Ctx).isError)
    }
}
