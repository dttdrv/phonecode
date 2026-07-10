package dev.phonecode.provider.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoaderTest {

    // Includes unknown keys (modalities, release_date) to prove tolerance.
    private val fixture = """
        {
          "anthropic": {"id":"anthropic","name":"Anthropic","api":"https://api.anthropic.com","env":["ANTHROPIC_API_KEY"],"doc":"https://d","models":{"claude-opus-4-8":{"id":"claude-opus-4-8","name":"Claude Opus 4.8","reasoning":true,"reasoning_options":[{"type":"effort","values":["low","medium","high","xhigh","max"]}],"tool_call":true,"attachment":true,"cost":{"input":15.0,"output":75.0,"cache_read":1.5},"limit":{"context":200000,"output":64000},"modalities":{"input":["text"],"output":["text"]},"release_date":"2026-01-01"}}},
          "openrouter": {"id":"openrouter","name":"OpenRouter","env":["OPENROUTER_API_KEY"],"models":{"z-model":{"id":"z-model","name":"Z Model"},"a-model":{"id":"a-model","name":"A Model"}}}
        }
    """.trimIndent()

    private class FakeCache(private val content: String?, private val age: Long?) : CatalogCache {
        private var written: String? = null
        override fun read(): String? = content ?: written
        override fun write(json: String) { written = json }
        override fun ageMillis(): Long? = if (read() == null) null else age
    }

    @Test fun freshCacheServedWithoutNetwork() = runBlocking {
        val result = CatalogLoader(OkHttpClient(), FakeCache(fixture, age = 1000)).load()
        assertEquals(Source.CACHE, result.source)
        assertNull(result.staleAgeMillis)
        assertEquals(2, result.catalog.size)
    }

    @Test fun toleratesUnknownKeys() {
        val catalog: Catalog = catalogJson.decodeFromString(fixture)
        val model = catalog.getValue("anthropic").models.getValue("claude-opus-4-8")
        assertTrue(model.reasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), model.reasoningOptions.single().values)
        assertTrue(model.toolCall)
        assertEquals(15.0, model.cost!!.input!!, 1e-9)
        assertEquals(200000L, model.limit!!.context)
    }

    @Test fun bundledFallbackWhenNoCacheAndNetworkFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val loader = CatalogLoader(
            httpClient = OkHttpClient(),
            cache = FakeCache(null, null),
            catalogUrl = server.url("/api.json").toString(),
            bundledFallback = { """{"openai":{"id":"openai","name":"OpenAI","models":{}}}""" },
        )
        val result = loader.load()
        assertEquals(Source.BUNDLED, result.source)
        assertEquals(setOf("openai"), result.catalog.keys)
        server.shutdown()
    }

    @Test fun corruptFreshCacheFallsThroughToBundled() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val loader = CatalogLoader(
            httpClient = OkHttpClient(),
            cache = FakeCache("{ this is not json", age = 1000),
            catalogUrl = server.url("/api.json").toString(),
            bundledFallback = { """{"openai":{"id":"openai","name":"OpenAI","models":{}}}""" },
        )
        assertEquals(Source.BUNDLED, loader.load().source)
        server.shutdown()
    }

    @Test fun malformedNetworkBodyIsNotCachedAndFallsBack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("<<garbage>>"))
        server.start()
        val cache = FakeCache(null, null)
        val loader = CatalogLoader(
            httpClient = OkHttpClient(),
            cache = cache,
            catalogUrl = server.url("/api.json").toString(),
            bundledFallback = { """{"openai":{"id":"openai","name":"OpenAI","models":{}}}""" },
        )
        assertEquals(Source.BUNDLED, loader.load().source)
        assertNull(cache.read()) // the malformed body must not poison the cache
        server.shutdown()
    }
}
