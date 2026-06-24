package dev.phonecode.tools.skills

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Progressive-disclosure skill loader. The model only ever sees each skill's name+description
 * (listed in this tool's [description]); calling `skill({name})` returns that skill's full
 * SKILL.md body wrapped in a `<skill_content>` block. Keeping the bodies out of the prompt until
 * requested is what stops a dozen skills from bloating (and invalidating the cache prefix of) every turn.
 */
class SkillTool(private val skills: List<SkillManifest>) : Tool {
    override val name = "skill"
    override val description: String = buildString {
        append("Load a skill's full instructions on demand. Call with the exact name of one skill below.")
        if (skills.isEmpty()) {
            append("\n(no skills are currently configured)")
        } else {
            append("\nAvailable skills:")
            skills.forEach { append("\n- ${it.name}: ${it.description}") }
        }
    }
    override val promptSnippet = "load a configured skill's full instructions on demand (progressive disclosure)"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "The exact name of the skill to load")
            }
        }
        put("required", buildJsonArray { add("name") })
        put("additionalProperties", false)
    }

    private val byName: Map<String, SkillManifest> = skills.associateBy { it.name }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val name = (args["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult("skill: missing 'name'", isError = true)
        val skill = byName[name] ?: return ToolResult(
            "skill: unknown skill '$name'. Available: ${skills.joinToString(", ") { it.name }.ifEmpty { "(none)" }}",
            isError = true,
        )
        return ToolResult("<skill_content name=\"${skill.name}\">\n${skill.body}\n</skill_content>")
    }
}
