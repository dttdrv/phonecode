package dev.phonecode.app.agent

import dev.phonecode.app.data.McpConfigLoad
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.SkillScope
import dev.phonecode.app.data.isSafeMcpEndpoint
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.mcp.McpServerConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

internal class ExtensionConfigReadTool(
    private val repository: McpSkillRepository,
    private val projectDirectory: () -> File,
) : Tool {
    override val name = "extension_read"
    override val description = "Inspect PhoneCode MCP servers and global or project skills without exposing saved header values."
    override val promptSnippet = "inspect configured MCP servers and skill files"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            enumProperty("action", "inventory", "read_skill")
            enumProperty("scope", "global", "project")
            stringProperty("name")
            stringProperty("path")
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = when (args.string("action")) {
        "inventory" -> inventory()
        "read_skill" -> {
            val scope = args.scope() ?: return ToolResult("extension_read: scope must be global or project", true)
            val skill = args.string("name").takeIf { it.isNotBlank() }
                ?: return ToolResult("extension_read: name is required", true)
            repository.readSkillFile(scope, skill, args.string("path").ifBlank { "SKILL.md" }, projectDirectory())
                .fold({ ToolResult(it) }, { ToolResult("extension_read: ${it.message}", true) })
        }
        else -> ToolResult("extension_read: action must be inventory or read_skill", true)
    }

    private fun inventory(): ToolResult {
        val mcp = when (val loaded = repository.loadMcpConfigState()) {
            is McpConfigLoad.Invalid -> "MCP configuration: ${loaded.message}"
            is McpConfigLoad.Ready -> if (loaded.config.mcp.isEmpty()) {
                "MCP servers: none"
            } else {
                loaded.config.mcp.entries.joinToString("\n", "MCP servers:\n") { (name, server) ->
                    val headers = server.headers.keys.sorted().joinToString().ifBlank { "none" }
                    "- $name: ${server.url} (${if (server.enabled) "enabled" else "disabled"}; header names: $headers)"
                }
            }
        }
        val inventory = repository.scanSkills(projectDirectory()).items
        val skills = if (inventory.isEmpty()) {
            "Skills: none"
        } else {
            inventory.joinToString("\n", "Skills:\n") { skill ->
                val issue = skill.issue?.let { "; $it" }.orEmpty()
                "- ${skill.scope.name.lowercase()}/${skill.name}: ${skill.status.name.lowercase()}$issue"
            }
        }
        return ToolResult("$mcp\n$skills")
    }
}

internal class ExtensionConfigWriteTool(
    private val repository: McpSkillRepository,
    private val projectDirectory: () -> File,
) : Tool {
    override val name = "extension_write"
    override val description = "Add, update, enable, disable, or remove an MCP server, or write and remove bounded global or project skill files."
    override val promptSnippet = "manage MCP servers and global or project skill files"
    override val mutating = true
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            enumProperty("action", "upsert_mcp", "remove_mcp", "set_mcp_enabled", "reset_mcp_config", "write_skill", "delete_skill")
            enumProperty("scope", "global", "project")
            stringProperty("name")
            stringProperty("original_name")
            stringProperty("url")
            stringProperty("path")
            stringProperty("content")
            putJsonObject("enabled") { put("type", "boolean") }
            putJsonObject("timeout") { put("type", "integer"); put("minimum", 1000); put("maximum", 60000) }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = when (args.string("action")) {
        "upsert_mcp" -> upsertMcp(args)
        "remove_mcp" -> repository.removeMcpServer(args.requiredName() ?: return ToolResult("extension_write: name is required", true))
            .fold({ ToolResult("MCP server removed") }, { ToolResult("extension_write: ${it.message}", true) })
        "set_mcp_enabled" -> {
            val name = args.requiredName() ?: return ToolResult("extension_write: name is required", true)
            val enabled = args.boolean("enabled") ?: return ToolResult("extension_write: enabled is required", true)
            repository.setMcpEnabled(name, enabled)
                .fold({ ToolResult("MCP server ${if (enabled) "enabled" else "disabled"}") }, { ToolResult("extension_write: ${it.message}", true) })
        }
        "reset_mcp_config" -> repository.replaceMcpConfig("""{"mcp":{}}""")
            .fold({ ToolResult("MCP configuration reset") }, { ToolResult("extension_write: ${it.message}", true) })
        "write_skill" -> {
            val scope = args.scope() ?: return ToolResult("extension_write: scope must be global or project", true)
            val name = args.requiredName() ?: return ToolResult("extension_write: name is required", true)
            val content = args.stringOrNull("content") ?: return ToolResult("extension_write: content is required", true)
            repository.writeSkillFile(scope, name, args.string("path").ifBlank { "SKILL.md" }, content, projectDirectory())
                .fold({ ToolResult("Skill file saved") }, { ToolResult("extension_write: ${it.message}", true) })
        }
        "delete_skill" -> {
            val scope = args.scope() ?: return ToolResult("extension_write: scope must be global or project", true)
            val name = args.requiredName() ?: return ToolResult("extension_write: name is required", true)
            repository.deleteEditableSkill(scope, name, projectDirectory())
                .fold({ ToolResult("Skill deleted") }, { ToolResult("extension_write: ${it.message}", true) })
        }
        else -> ToolResult("extension_write: unsupported action", true)
    }

    private fun upsertMcp(args: JsonObject): ToolResult {
        if ("headers" in args) return ToolResult("extension_write: set MCP header values in Settings", true)
        val name = args.requiredName() ?: return ToolResult("extension_write: name is required", true)
        val loaded = repository.loadMcpConfigState()
        if (loaded is McpConfigLoad.Invalid) return ToolResult("extension_write: ${loaded.message}", true)
        val servers = (loaded as McpConfigLoad.Ready).config.mcp
        val original = args.string("original_name").ifBlank { name.takeIf(servers::containsKey) }
        val current = servers[original ?: name]
        val url = args.string("url").ifBlank { current?.url.orEmpty() }
        if (!isSafeMcpEndpoint(url)) return ToolResult("extension_write: use HTTPS, or HTTP only for localhost", true)
        val timeout = args.long("timeout") ?: current?.timeout ?: 5_000
        if (timeout !in 1_000..60_000) return ToolResult("extension_write: timeout must be between 1000 and 60000 ms", true)
        val server = McpServerConfig(
            url = url,
            headers = current?.headers.orEmpty(),
            // New remote servers stay out of the runtime until their discovered tools have been
            // reviewed. Existing servers preserve their state and callers may explicitly enable.
            enabled = args.boolean("enabled") ?: current?.enabled ?: false,
            timeout = timeout,
        )
        return repository.upsertMcpServer(original, name, server)
            .fold({ ToolResult("MCP server saved") }, { ToolResult("extension_write: ${it.message}", true) })
    }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.requiredName(): String? = string("name").trim().takeIf { it.isNotEmpty() }

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.scope(): SkillScope? = when (string("scope")) {
    "global" -> SkillScope.GLOBAL
    "project" -> SkillScope.PROJECT
    else -> null
}

private fun kotlinx.serialization.json.JsonObjectBuilder.enumProperty(name: String, vararg values: String) {
    putJsonObject(name) {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String) {
    putJsonObject(name) { put("type", "string") }
}
