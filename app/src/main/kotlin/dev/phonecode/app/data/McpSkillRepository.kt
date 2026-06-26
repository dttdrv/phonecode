package dev.phonecode.app.data

import dev.phonecode.tools.mcp.McpConfig
import dev.phonecode.tools.mcp.parseMcpConfig
import dev.phonecode.tools.mcp.serialize
import dev.phonecode.tools.skills.SkillManifest
import dev.phonecode.tools.skills.parseSkillMarkdown
import java.io.File

/**
 * On-device store for the MCP + skills configuration the agent and the Settings UI both manage.
 * MCP servers live in an OpenCode-compatible `opencode.json` (`{ "mcp": { ... } }`); skills are
 * discovered as `SKILL.md` files under `skills/` and `.claude/skills/` (Claude-Code interop).
 * The config directory is the same path injected into the system prompt, so the agent can self-edit it.
 */
class McpSkillRepository(private val configDir: File) {
    private val mcpFile = File(configDir, "opencode.json")

    fun loadMcpConfig(): McpConfig =
        if (mcpFile.exists()) runCatching { parseMcpConfig(mcpFile.readText()) }.getOrDefault(McpConfig()) else McpConfig()

    fun saveMcpConfig(config: McpConfig) {
        configDir.mkdirs()
        mcpFile.writeText(config.serialize())
    }

    /**
     * Copy any bundled `assets/skills/<name>/SKILL.md` into the config skills dir, once ever (gated by a
     * marker), so built-in skills appear out of the box yet the user can still edit or delete them afterwards.
     */
    fun seedBundledSkills(assets: android.content.res.AssetManager) {
        val marker = File(configDir, ".skills-seeded")
        if (marker.exists()) return
        runCatching {
            assets.list("skills")?.forEach { name ->
                val target = File(configDir, "skills/$name/SKILL.md")
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    assets.open("skills/$name/SKILL.md").use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        configDir.mkdirs()
        runCatching { marker.writeText("1") }
    }

    /** Discover every `SKILL.md` under `skills/` and `.claude/skills/`, de-duplicated by name. */
    fun discoverSkills(): List<SkillManifest> {
        val roots = listOf(File(configDir, "skills"), File(configDir, ".claude/skills"))
        return roots.filter { it.isDirectory }
            .flatMap { root ->
                // Guard the whole walk: a broken symlink / unreadable subtree yields a partial list, never a throw.
                runCatching {
                    root.walkTopDown()
                        .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                        .mapNotNull { file -> runCatching { parseSkillMarkdown(file.readText()) }.getOrNull() }
                        .toList()
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.name }
    }
}
