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

class WebSearchToolTest {

    private lateinit var server: MockWebServer

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

    private fun queryArgs(query: String): JsonObject = buildJsonObject { put("query", query) }
    private fun tool() = WebSearchTool(OkHttpClient(), server.url("/html/").toString())

    private val ddgHtml = """
        <div class="result results_links">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&rut=x">Kotlin &amp; Lang</a>
          <a class="result__snippet" href="//x">Concise JVM language</a>
        </div>
        <div class="result results_links">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fktor.io%2F">Ktor</a>
          <a class="result__snippet">Async framework</a>
        </div>
    """.trimIndent()

    @Test fun parsesDuckDuckGoResults() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(ddgHtml))
        val result = tool().execute(queryArgs("kotlin lang"), Ctx)

        assertFalse(result.isError)
        assertTrue(result.output.contains("Kotlin & Lang")) // entity decoded
        assertTrue(result.output.contains("https://kotlinlang.org/")) // uddg-unwrapped real url
        assertTrue(result.output.contains("Concise JVM language"))
        assertTrue(result.output.contains("Ktor"))
        assertTrue(result.output.contains("https://ktor.io/"))

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("q=kotlin")) // query is URL-encoded into the request
    }

    @Test fun noResultsMessage() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>nothing</body></html>"))
        val result = tool().execute(queryArgs("zzz"), Ctx)
        assertFalse(result.isError)
        assertTrue(result.output.contains("No results"))
    }

    @Test fun missingQueryIsError() = runBlocking {
        assertTrue(tool().execute(JsonObject(emptyMap()), Ctx).isError)
    }

    @Test fun non2xxIsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        val result = tool().execute(queryArgs("kotlin"), Ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("503"))
    }
}
