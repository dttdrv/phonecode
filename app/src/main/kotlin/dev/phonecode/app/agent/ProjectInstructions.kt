package dev.phonecode.app.agent

import java.io.File

internal fun loadProjectInstructions(workspace: File, custom: String = ""): List<String> = buildList {
    custom.trim().takeIf { it.isNotEmpty() }?.let { add("Misul Agent preferences:\n${it.take(MAX_INSTRUCTION_CHARS)}") }
    val root = runCatching { workspace.canonicalFile }.getOrNull() ?: return@buildList
    INSTRUCTION_FILES.forEach { name ->
        val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return@forEach
        if (file.parentFile != root || !file.isFile || file.length() > MAX_INSTRUCTION_BYTES) return@forEach
        val content = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (content.isNotEmpty() && '\u0000' !in content) add("$name:\n$content")
    }
}

private val INSTRUCTION_FILES = listOf("AGENTS.md", "CLAUDE.md")
private const val MAX_INSTRUCTION_BYTES = 64L * 1024L
private const val MAX_INSTRUCTION_CHARS = 64 * 1024
