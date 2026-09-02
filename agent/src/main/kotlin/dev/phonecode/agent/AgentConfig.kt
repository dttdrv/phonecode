package dev.phonecode.agent

import dev.phonecode.provider.domain.ReasoningEffort

data class AgentConfig(
    val model: String,
    val environment: AgentEnvironment,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** Safety cap on agentic turns; on the last one tools are disabled and the model is asked to wrap up. */
    val maxSteps: Int = 200,
    /** AGENTS.md / CLAUDE.md contents discovered by :app, injected into the system prompt. */
    val projectInstructions: List<String> = emptyList(),
    val mcpInstructions: List<String> = emptyList(),
    /** Stable id used for OpenAI-family prompt caching (Anthropic caching is automatic). */
    val sessionId: String? = null,
)

/**
 * Per-turn model + effort, resolved fresh each turn so the model can switch mid-session.
 * [contextLimit] (from the model catalog) drives compaction; null disables it. [maxOutput] caps
 * the response length via ChatRequest.maxTokens; null lets the provider/builder default apply.
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
