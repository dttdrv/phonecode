package dev.phonecode.agent

import dev.phonecode.provider.domain.ReasoningEffort

data class AgentConfig(
    val model: String,
    val mode: AgentMode,
    val environment: AgentEnvironment,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** Safety cap on agentic turns; on the last one tools are disabled and the model is asked to wrap up. */
    val maxSteps: Int = 200,
    /** AGENTS.md / CLAUDE.md contents discovered by :app, injected into the system prompt. */
    val projectInstructions: List<String> = emptyList(),
    /** Skill name+description for progressive disclosure (bodies loaded on demand). */
    val skills: List<SkillInfo> = emptyList(),
    /** Stable id used for OpenAI-family prompt caching (Anthropic caching is automatic). */
    val sessionId: String? = null,
)

data class SkillInfo(val name: String, val description: String)

/**
 * Per-turn model + effort, resolved fresh each turn so the model can switch mid-session.
 * [contextLimit]/[maxOutput] (from the model catalog) drive compaction; null disables it.
 */
data class TurnSettings(
    val model: String,
    val reasoningEffort: ReasoningEffort,
    val contextLimit: Long? = null,
    val maxOutput: Long? = null,
)

/**
 * A source of queued user messages. Steering = delivered mid-task (after the
 * current tool batch); follow-up = delivered only when the agent would otherwise
 * stop. Returns an empty list when nothing is queued.
 */
fun interface MessageSource {
    suspend fun poll(): List<String>

    companion object {
        val EMPTY = MessageSource { emptyList() }
    }
}
