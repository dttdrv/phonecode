package dev.phonecode.tools.mcp

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Sanitize a name for the model-facing tool id: only [a-zA-Z0-9_-]. */
internal fun sanitizeMcpName(value: String): String = value.replace(Regex("[^a-zA-Z0-9_-]"), "_")

internal fun mcpToolName(serverName: String, toolName: String): String {
    val sanitized = "mcp_${sanitizeMcpName(serverName)}_${sanitizeMcpName(toolName)}"
    val source = "${serverName.length}:$serverName$toolName"
    val suffix = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }
    return sanitized.take(MAX_TOOL_NAME_LENGTH - suffix.length - 1) + "_" + suffix
}

/** Adapts a remote MCP tool into a PhoneCode [Tool], namespaced as sanitize(server)_sanitize(tool). */
class McpTool(val serverName: String, private val def: McpToolDef, private val client: McpClient) : Tool {
    override val name: String = mcpToolName(serverName, def.name)
    val displayName: String = def.title.ifBlank { def.name }.replace('_', ' ').replace('-', ' ')
    override val description: String = def.description.ifBlank { def.title.ifBlank { def.name } }
    override val parameters: JsonObject = def.inputSchema
    override val mutating: Boolean = true // MCP tools may have side effects; gate through permission
    override val promptSnippet: String = description

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = try {
            val output = client.callTool(def.name, args)
            if (output.startsWith("ERROR: ")) ToolResult(output.removePrefix("ERROR: "), isError = true)
            else ToolResult(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult("mcp '${def.name}' failed: ${error.message}", isError = true)
        }
}

private const val MAX_TOOL_NAME_LENGTH = 64
