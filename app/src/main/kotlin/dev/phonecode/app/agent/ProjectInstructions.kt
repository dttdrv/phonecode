package dev.phonecode.app.agent

import dev.phonecode.app.data.AppSettings
import java.io.File

internal fun AppSettings.profileForPrompt(): String {
    if (!usePersonalization) return ""
    val details = buildList {
        preferredName.trim().replace(Regex("\\s+"), " ").take(80).takeIf { it.isNotBlank() }
            ?.let { add("Preferred name: $it") }
        occupation.trim().replace(Regex("\\s+"), " ").take(120).takeIf { it.isNotBlank() }
            ?.let { add("Occupation: $it") }
        aboutYou.trim().take(1_500).takeIf { it.isNotBlank() }
            ?.let { add("More about the user: $it") }
        responseStyle.instruction?.let { add("Response style: $it") }
    }
    return details.joinToString("\n")
}

internal fun loadProjectInstructions(workspace: File, settings: AppSettings): List<String> =
    loadProjectInstructions(
        workspace,
        custom = settings.customInstructions.takeIf { settings.usePersonalization }.orEmpty(),
        profile = settings.profileForPrompt(),
    )

internal fun loadProjectInstructions(workspace: File, custom: String = "", profile: String = ""): List<String> = buildList {
    profile.trim().takeIf { it.isNotEmpty() }?.let { add("User profile and response preferences:\n${it.take(MAX_INSTRUCTION_CHARS)}") }
    custom.trim().takeIf { it.isNotEmpty() }?.let { add("PhoneCode preferences:\n${it.take(MAX_INSTRUCTION_CHARS)}") }
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
