package dev.phonecode.tools.skills

/** A skill discovered from a SKILL.md file: name + description (for the prompt) and the body (loaded on demand). */
data class SkillManifest(val name: String, val description: String, val body: String)

private val FRONTMATTER = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$", RegexOption.DOT_MATCHES_ALL)

/** Parses `SKILL.md` (YAML-ish frontmatter with `name`/`description`, then the markdown body). Null if no valid name. */
fun parseSkillMarkdown(content: String): SkillManifest? {
    // Tolerate a UTF-8 BOM and leading blank lines/whitespace before the opening `---`.
    val normalized = content.replace("\r\n", "\n").removePrefix("﻿").trimStart()
    val match = FRONTMATTER.find(normalized) ?: return null
    val front = match.groupValues[1]
    val body = match.groupValues[2].trim()
    val name = frontValue(front, "name")?.takeIf { it.isNotBlank() } ?: return null
    val description = frontValue(front, "description").orEmpty()
    return SkillManifest(name, description, body)
}

private fun frontValue(front: String, key: String): String? =
    front.lineSequence().firstNotNullOfOrNull { line ->
        Regex("^$key:\\s*(.*)$").find(line.trim())?.groupValues?.get(1)?.trim()?.trim('"', '\'')
    }
