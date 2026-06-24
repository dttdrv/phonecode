package dev.phonecode.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A remote (HTTP/SSE) MCP server. OpenCode-compatible shape: `mcp.<name> = { type, url, headers, enabled }`. */
@Serializable
data class McpServerConfig(
    val type: String = "remote",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class McpConfig(val mcp: Map<String, McpServerConfig> = emptyMap())

private val mcpJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/** Parses the MCP config; tolerates malformed input by returning an empty config. */
fun parseMcpConfig(json: String): McpConfig =
    runCatching { mcpJson.decodeFromString<McpConfig>(json) }.getOrDefault(McpConfig())

fun McpConfig.serialize(): String = mcpJson.encodeToString(McpConfig.serializer(), this)
