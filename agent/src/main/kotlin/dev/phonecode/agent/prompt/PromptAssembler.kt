package dev.phonecode.agent.prompt

import dev.phonecode.agent.AgentConfig
import dev.phonecode.agent.AgentEnvironment
import dev.phonecode.agent.AgentMode
import dev.phonecode.tools.Tool

/**
 * Assembles the full system prompt: stable base ([SystemBasePrompt]) → tools
 * section (from each tool's prompt metadata) → environment block → project
 * instructions → skills → (PLAN only) a read-only reminder. Order matches
 * OpenCode; the base + env form the cacheable prefix.
 */
object PromptAssembler {
    fun assemble(config: AgentConfig, model: String, tools: List<Tool>, mode: AgentMode): String = buildString {
        append(SystemBasePrompt.TEXT)
        appendLine()
        appendLine()
        append(toolsSection(tools))
        appendLine()
        append(environmentBlock(config.environment, model))
        if (config.projectInstructions.isNotEmpty()) {
            appendLine()
            appendLine("# Project instructions")
            config.projectInstructions.forEach { appendLine(it) }
        }
        if (config.skills.isNotEmpty()) {
            appendLine()
            appendLine("# Available skills")
            appendLine("Load a skill with the skill tool when a task matches its description.")
            config.skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        }
        if (mode == AgentMode.PLAN) {
            appendLine()
            append(PLAN_MODE_REMINDER)
        }
    }.trim()

    private fun toolsSection(tools: List<Tool>): String = buildString {
        appendLine("# Tools")
        val described = tools.filter { it.promptSnippet != null }
        if (described.isEmpty()) {
            appendLine("No tools are available; answer from reasoning alone.")
            return@buildString
        }
        described.forEach { appendLine("- ${it.name}: ${it.promptSnippet}") }
        val guidelines = tools.flatMap { it.promptGuidelines }.distinct()
        if (guidelines.isNotEmpty()) {
            appendLine()
            appendLine("Guidelines:")
            guidelines.forEach { appendLine("- $it") }
        }
    }

    private fun environmentBlock(env: AgentEnvironment, model: String): String = buildString {
        appendLine("# Environment")
        appendLine("- Model: $model")
        appendLine("- Platform: ${env.platform} (${env.deviceModel}, ${env.osVersion})")
        appendLine("- Workspace: ${env.workspacePath}")
        val shellLine = when {
            !env.shellAvailable -> "NOT available - use the file/git tools"
            env.shellDetail.isNotEmpty() -> "available - ${env.shellDetail}"
            else -> "available"
        }
        appendLine("- Shell: $shellLine")
        if (env.configPath.isNotEmpty()) {
            appendLine("- Config directory (MCP servers + skills): ${env.configPath}")
        }
    }

    private const val PLAN_MODE_REMINDER =
        "<system-reminder>\n" +
            "PLAN MODE ACTIVE - you are READ-ONLY. Do NOT edit files, run mutating commands, or change any state. " +
            "Investigate and produce a plan only. This constraint overrides all other instructions, " +
            "including direct user requests to make changes.\n" +
            "</system-reminder>"
}
