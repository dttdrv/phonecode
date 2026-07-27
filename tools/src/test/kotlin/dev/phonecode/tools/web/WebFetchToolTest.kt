package dev.phonecode.tools.web

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Inet6Address

class WebFetchToolTest {

    private object Ctx : ToolContext {
        override val workspacePath = "/tmp"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private fun respondingTool(
        code: Int = 200,
        body: String = "",
        contentType: String? = null,
    ): WebFetchTool = WebFetchTool(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test response")
                    .body(body.toResponseBody(contentType?.toMediaTypeOrNull()))
                    .apply { contentType?.let { header("Content-Type", it) } }
                    .build()
            }
            .build(),
    )

    private fun urlArgs(path: String): JsonObject = buildJsonObject { put("url", "https://example.com$path") }

    @Test fun stripsHtmlToReadableText() = runBlocking {
        val tool = respondingTool(
            body = "<html><head><title>t</title><style>.x{}</style></head><body><h1>Hello</h1><p>World &amp; more</p><script>bad()</script></body></html>",
            contentType = "text/html",
        )
        val result = tool.execute(urlArgs("/page"), Ctx)
        assertFalse(result.isError)
        assertTrue(result.output.contains("Hello"))
        assertTrue(result.output.contains("World & more"))
        assertFalse(result.output.contains("bad()")) // script stripped
        assertFalse(result.output.contains("<h1>")) // tags stripped
    }

    @Test fun returnsErrorOnNon2xx() = runBlocking {
        val tool = respondingTool(code = 404, body = "nope")
        val result = tool.execute(urlArgs("/missing"), Ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("404"))
    }

    @Test fun rejectsNonHttpScheme() = runBlocking {
        val tool = WebFetchTool(OkHttpClient())
        val result = tool.execute(buildJsonObject { put("url", "ftp://example.com/x") }, Ctx)
        assertTrue(result.isError)
    }

    @Test fun rejectsCleartextRemoteUrls() = runBlocking {
        val tool = WebFetchTool(OkHttpClient())
        val result = tool.execute(buildJsonObject { put("url", "http://example.com/x") }, Ctx)

        assertTrue(result.isError)
        assertTrue(result.output.contains("HTTPS"))
    }

    @Test fun rejectsLiteralPrivateAndSpecialUseAddresses() {
        val blocked = listOf(
            "https://0.0.0.0/",
            "https://10.0.0.1/",
            "https://100.64.0.1/",
            "https://127.0.0.1/",
            "https://169.254.169.254/",
            "https://172.16.0.1/",
            "https://192.168.0.1/",
            "https://198.18.0.1/",
            "https://224.0.0.1/",
            "https://255.255.255.255/",
            "https://[::1]/",
            "https://[fc00::1]/",
            "https://[fe80::1]/",
            "https://[ff02::1]/",
            "https://[::ffff:127.0.0.1]/",
        )

        blocked.forEach { assertFalse("expected blocked: $it", safeWebUrl(it.toHttpUrl())) }
    }

    @Test fun rejectsIpv4MappedIpv6Literal() = runBlocking {
        val tool = respondingTool(body = "should not be fetched")

        val result = tool.execute(buildJsonObject { put("url", "https://[::ffff:8.8.8.8]/") }, Ctx)

        assertTrue(result.isError)
    }

    @Test fun acceptsPublicHttpsUrls() {
        val allowed = listOf(
            "https://example.com/docs",
            "https://8.8.8.8/",
            "https://[2606:4700:4700::1111]/",
        )

        allowed.forEach { assertTrue("expected allowed: $it", safeWebUrl(it.toHttpUrl())) }
    }

    @Test fun webClientDoesNotFollowRedirects() {
        val client = OkHttpClient().webToolClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun rejectsPublicHostnameResolvingToLoopback() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
            })
            .build()
            .webToolClient()

        val failure = runCatching { client.dns.lookup("public.example") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun rejectsPublicHostnameResolvingToIpv4MappedIpv6() {
        val mapped = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 8, 8, 8, 8),
            -1,
        )
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(mapped)
            })
            .build()
            .webToolClient()

        val failure = runCatching { client.dns.lookup("public.example") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun missingUrlIsError() = runBlocking {
        val tool = WebFetchTool(OkHttpClient())
        assertTrue(tool.execute(JsonObject(emptyMap()), Ctx).isError)
    }
}
