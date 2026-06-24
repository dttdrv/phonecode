package dev.phonecode.tools.web

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a URL over HTTP(S) and returns its content as text (mirrors OpenCode `webfetch`). HTML is reduced
 * to readable text unless `format=html`. Read-only research: visible in PLAN, no permission prompt - but
 * every call shows up as a tool-activity line with its URL, so the user always sees what was fetched.
 */
class WebFetchTool(private val http: OkHttpClient) : Tool {
    override val name = "webfetch"
    override val description =
        "Fetch a URL over HTTP(S) and return its content. HTML is converted to readable text by default; " +
            "pass format=html for raw HTML or format=text/markdown for text. Use to read docs or web pages."
    override val promptSnippet = "fetch a URL and read its content as text"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "The http(s) URL to fetch.") }
            putJsonObject("format") {
                put("type", "string")
                put("description", "text (default) | markdown | html")
            }
        }
        put("required", buildJsonArray { add("url") })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val url = (args["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult("webfetch: missing 'url'", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult("webfetch: url must start with http:// or https://", isError = true)
        }
        val format = (args["format"] as? JsonPrimitive)?.content ?: "text"
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult("webfetch: HTTP ${response.code} for $url", isError = true)
                    }
                    val body = response.body?.string().orEmpty()
                    val contentType = response.header("Content-Type").orEmpty()
                    val rendered = if (format == "html" || !(contentType.contains("html", true) || looksLikeHtml(body))) {
                        body
                    } else {
                        htmlToText(body)
                    }
                    val output = if (rendered.length > MAX_CHARS) {
                        rendered.take(MAX_CHARS) + "\n\n[truncated at $MAX_CHARS characters]"
                    } else {
                        rendered
                    }
                    ToolResult(output.ifBlank { "(empty response)" })
                }
            }.getOrElse { ToolResult("webfetch: ${it.message}", isError = true) }
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<body")
    }

    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(SCRIPT, " ").replace(STYLE, " ").replace(HEAD, " ")
        s = s.replace(TAG, " ")
        ENTITIES.forEach { (from, to) -> s = s.replace(from, to) }
        s = s.replace(HORIZONTAL_WS, " ").replace(EXTRA_BLANK_LINES, "\n\n")
        return s.trim()
    }

    private companion object {
        const val MAX_CHARS = 100_000
        const val USER_AGENT = "PhoneCode/0.1 (+https://phonecode.dev)"
        val SCRIPT = Regex("(?is)<script.*?</script>")
        val STYLE = Regex("(?is)<style.*?</style>")
        val HEAD = Regex("(?is)<head.*?</head>")
        val TAG = Regex("(?s)<[^>]+>")
        val HORIZONTAL_WS = Regex("[ \\t]+")
        val EXTRA_BLANK_LINES = Regex("\\n\\s*\\n\\s*\\n+")
        val ENTITIES = listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        )
    }
}
