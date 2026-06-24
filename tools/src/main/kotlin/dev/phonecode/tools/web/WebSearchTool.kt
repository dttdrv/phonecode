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
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Web search via DuckDuckGo's HTML endpoint - free and keyless. (OpenCode's own websearch uses Parallel/Exa,
 * which require paid API keys; the requirement here is a backend that is free at all times, so we parse the
 * DuckDuckGo SERP instead.) Returns the top results as title + url + snippet; pair with webfetch to read a page.
 * Read-only research: visible in PLAN, no permission prompt. [endpoint] is injectable so tests can point at a stub.
 */
class WebSearchTool(
    private val http: OkHttpClient,
    private val endpoint: String = DDG_HTML,
) : Tool {
    override val name = "websearch"
    override val description =
        "Search the web for current information and return the top results as title + url + snippet (free, no API " +
            "key - uses DuckDuckGo). If you have your own built-in/native web search, prefer that; use this as the " +
            "fallback (e.g. when your native search is unavailable, rate-limited, or you've lost access to it). " +
            "Then read a page with webfetch. Include the current year in the query when searching recent topics."
    override val promptSnippet =
        "search the web (free DuckDuckGo fallback; prefer your own native web search when you have it)"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "The search query.") }
            putJsonObject("count") {
                put("type", "integer")
                put("description", "Maximum number of results to return (default $DEFAULT_COUNT).")
            }
        }
        put("required", buildJsonArray { add("query") })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult("websearch: missing 'query'", isError = true)
        val count = (args["count"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, MAX_COUNT) ?: DEFAULT_COUNT
        val url = endpoint + (if (endpoint.contains("?")) "&" else "?") + "q=" + URLEncoder.encode(query, "UTF-8")

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult("websearch: HTTP ${response.code} from search backend", isError = true)
                    }
                    val results = parseResults(response.body?.string().orEmpty()).take(count)
                    if (results.isEmpty()) ToolResult("No results for \"$query\".") else ToolResult(format(query, results))
                }
            }.getOrElse { ToolResult("websearch: ${it.message}", isError = true) }
        }
    }

    /** Parse DuckDuckGo HTML result blocks: each `result__a` anchor (title + wrapped href) plus its `result__snippet`. */
    private fun parseResults(html: String): List<Result> {
        val snippets = SNIPPET.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return TITLE.findAll(html).toList().mapIndexed { index, match ->
            Result(clean(match.groupValues[2]), decodeHref(match.groupValues[1]), snippets.getOrElse(index) { "" })
        }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
    }

    /** DuckDuckGo wraps result links as `//duckduckgo.com/l/?uddg=<encoded-target>&...`; unwrap to the real URL. */
    private fun decodeHref(href: String): String {
        val marker = "uddg="
        val at = href.indexOf(marker)
        if (at < 0) return if (href.startsWith("//")) "https:$href" else href
        val encoded = href.substring(at + marker.length).substringBefore("&")
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private fun clean(raw: String): String =
        decodeEntities(raw.replace(TAG, "")).replace(WS, " ").trim()

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")

    private fun format(query: String, results: List<Result>): String =
        "Search results for \"$query\":\n" + results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}" + if (r.snippet.isNotBlank()) "\n   ${r.snippet}" else ""
        }.joinToString("\n\n")

    private data class Result(val title: String, val url: String, val snippet: String)

    private companion object {
        const val DDG_HTML = "https://html.duckduckgo.com/html/"
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 20
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) PhoneCode/0.1"
        val TITLE = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val SNIPPET = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val WS = Regex("\\s+")
    }
}
