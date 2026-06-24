package dev.phonecode.tools.mcp

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/** Sanitize a name for the model-facing tool id: only [a-zA-Z0-9_-]. */
internal fun sanitizeMcpName(value: String): String = value.replace(Regex("[^a-zA-Z0-9_-]"), "_")

/** Adapts a remote MCP tool into a PhoneCode [Tool], namespaced as sanitize(server)_sanitize(tool). */
class McpTool(serverName: String, private val def: McpToolDef, private val client: McpClient) : Tool {
    override val name: String = "${sanitizeMcpName(serverName)}_${sanitizeMcpName(def.name)}"
    override val description: String = def.description
    override val parameters: JsonObject = def.inputSchema
    override val mutating: Boolean = true // MCP tools may have side effects; gate through permission
    override val promptSnippet: String = def.description.ifBlank { "MCP tool ${def.name}" }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
        runCatching { ToolResult(client.callTool(def.name, args)) }
            .getOrElse { ToolResult("mcp '${def.name}' failed: ${it.message}", isError = true) }
}
