package dev.phonecode.tools.mcp

import dev.phonecode.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class McpToolDef(val name: String, val description: String, val inputSchema: JsonObject)

private val JSON_MEDIA = "application/json".toMediaType()

/**
 * Connect every enabled remote MCP server; a server that fails to connect is skipped, not fatal.
 * Servers connect CONCURRENTLY with a per-server time bound (review #8) - one slow or stalled
 * endpoint must never hold the whole registry rebuild hostage.
 */
suspend fun connectMcpServers(config: McpConfig, http: OkHttpClient): List<Tool> =
    coroutineScope {
        config.mcp.filter { it.value.enabled }.map { (name, server) ->
            async {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching { McpClient(name, server, http).connect() }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }

private const val CONNECT_TIMEOUT_MS = 20_000L

/** Minimal Remote MCP client: JSON-RPC 2.0 over Streamable-HTTP; the response may be JSON or SSE. */
class McpClient(
    private val serverName: String,
    private val config: McpServerConfig,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var sessionId: String? = null
    private var nextId = 1

    // Handshake requests get a HARD OkHttp call timeout: withTimeoutOrNull only abandons the
    // waiting coroutine - the blocking execute() and its socket would otherwise live on for the
    // full read timeout (verification finding). Derived clients share the pool, so this is cheap.
    // Tool CALLS keep the long-lived client: a legitimate MCP tool may stream for minutes.
    private val handshakeHttp = http.newBuilder()
        .callTimeout(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private var handshaking = false

    suspend fun connect(): List<Tool> {
        handshaking = true
        try {
            val init = request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    put("capabilities", buildJsonObject {})
                    putJsonObject("clientInfo") { put("name", "PhoneCode"); put("version", "0.1.0") }
                },
            )
            // A failed/absent initialize means a dead session - contribute no tools rather than listing on it.
            if (init == null || init["error"] != null) return emptyList()
            notify("notifications/initialized")
            return listTools().map { McpTool(serverName, it, this) }
        } finally {
            handshaking = false
        }
    }

    suspend fun listTools(): List<McpToolDef> {
        val out = mutableListOf<McpToolDef>()
        var cursor: String? = null
        do {
            val message = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } }) ?: break
            val result = message["result"] as? JsonObject ?: break
            (result["tools"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val description = (obj["description"] as? JsonPrimitive)?.contentOrNull ?: ""
                // Normalize a missing/typeless schema to a valid object schema; some providers reject the
                // whole request (every tool) if any tool's parameters aren't a proper JSON-Schema object.
                val schema = (obj["inputSchema"] as? JsonObject)?.takeIf { it.containsKey("type") } ?: EMPTY_SCHEMA
                out += McpToolDef(name, description, schema)
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && out.size < MAX_TOOLS)
        return out
    }

    suspend fun callTool(toolName: String, args: JsonObject): String {
        val message = request("tools/call", buildJsonObject { put("name", toolName); put("arguments", args) })
            ?: return "(no response from MCP server)"
        (message["error"] as? JsonObject)?.let { error ->
            return "ERROR: " + ((error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString())
        }
        val result = message["result"] as? JsonObject ?: return "(no response from MCP server)"
        val content = (result["content"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        return if (isError) "ERROR: $content" else content.ifEmpty { "(empty result)" }
    }

    /** One JSON-RPC request → its response message (the object carrying `result` or `error`), or null on transport failure. */
    private suspend fun request(method: String, params: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val id = nextId++
        val payload = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        val response = post(payload.toString()) ?: return@withContext null
        extractMessage(response, id)
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", buildJsonObject {}) }.toString())
        Unit
    }

    private fun post(body: String): String? {
        val request = Request.Builder()
            .url(config.url)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                config.headers.forEach { (key, value) -> header(key, value) }
                sessionId?.let { header("Mcp-Session-Id", it) }
            }
            .build()
        return runCatching {
            (if (handshaking) handshakeHttp else http).newCall(request).execute().use { response ->
                response.header("Mcp-Session-Id")?.let { sessionId = it }
                response.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Body is a single JSON-RPC object, or SSE. Select the actual RESPONSE - the frame whose `id` matches the
     * request, else the last frame carrying `result`/`error` - so a trailing `notifications/progress` event
     * (which servers routinely interleave) is not mistaken for the response.
     */
    private fun extractMessage(body: String, id: Int): JsonObject? {
        val candidates = parseCandidates(body)
        return candidates.firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == id }
            ?: candidates.lastOrNull { it.containsKey("result") || it.containsKey("error") }
            ?: candidates.lastOrNull()
    }

    private fun parseCandidates(body: String): List<JsonObject> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()?.let { listOf(it) }
                ?: emptyList()
        }
        return trimmed.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            .toList()
    }

    private companion object {
        const val MAX_TOOLS = 500
        val EMPTY_SCHEMA = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
    }
}
