package dev.phonecode.tools.web

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {

    private var requestedUrl: HttpUrl? = null

    private object Ctx : ToolContext {
        override val workspacePath = "/tmp"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private fun queryArgs(query: String): JsonObject = buildJsonObject { put("query", query) }
    private fun tool(body: String = "", code: Int = 200) = WebSearchTool(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrl = chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test response")
                    .body(body.toResponseBody())
                    .build()
            }
            .build(),
        "https://html.duckduckgo.com/html/",
    )

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
        val result = tool(body = ddgHtml).execute(queryArgs("kotlin lang"), Ctx)

        assertFalse(result.isError)
        assertTrue(result.output.contains("Kotlin & Lang")) // entity decoded
        assertTrue(result.output.contains("https://kotlinlang.org/")) // uddg-unwrapped real url
        assertTrue(result.output.contains("Concise JVM language"))
        assertTrue(result.output.contains("Ktor"))
        assertTrue(result.output.contains("https://ktor.io/"))

        assertTrue(requestedUrl!!.encodedQuery!!.contains("q=kotlin")) // query is URL-encoded into the request
    }

    @Test fun noResultsMessage() = runBlocking {
        val result = tool(body = "<html><body>nothing</body></html>").execute(queryArgs("zzz"), Ctx)
        assertFalse(result.isError)
        assertTrue(result.output.contains("No results"))
    }

    @Test fun missingQueryIsError() = runBlocking {
        assertTrue(tool().execute(JsonObject(emptyMap()), Ctx).isError)
    }

    @Test fun non2xxIsError() = runBlocking {
        val result = tool(body = "down", code = 503).execute(queryArgs("kotlin"), Ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("503"))
    }
}
