package dev.phonecode.agent

import dev.phonecode.agent.prompt.PromptAssembler
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.provider.domain.ToolDef
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolRegistry
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The agentic loop - Pi's nested shape with OpenCode's discipline. The inner loop
 * runs assistant turns: stream → accumulate text/reasoning/tool-calls → if tools
 * were requested, execute them (parallel, with a sequential opt-in), feed results
 * back, and continue; it ends when the model stops requesting tools and no steering
 * is queued. The outer loop re-enters when a follow-up message is queued. A safety
 * [AgentConfig.maxSteps] cap disables tools on the last turn and asks the model to
 * wrap up (rather than hard-cutting). Provider-agnostic via [LlmProvider]; emits
 * UI-facing [AgentEvent]s; [TurnSettings] are resolved per turn so the model can
 * switch mid-session.
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val context: ToolContext,
    private val config: AgentConfig,
    private val steering: MessageSource = MessageSource.EMPTY,
    private val followUp: MessageSource = MessageSource.EMPTY,
    private val turnSettings: suspend () -> TurnSettings = { TurnSettings(config.model, config.reasoningEffort) },
    private val contextManager: ContextManager = ContextManager(provider),
    /** Resolved per turn so a `plan_exit` approval can flip PLAN→BUILD mid-run. Defaults to the static config mode. */
    private val modeProvider: suspend () -> AgentMode = { config.mode },
) {
    private val argsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(history: List<ChatMessage>, userInput: String): Flow<AgentEvent> =
        run(history, listOf(MessagePart.Text(userInput)))

    fun run(history: List<ChatMessage>, userParts: List<MessagePart>): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        messages += ChatMessage(Role.USER, userParts)

        val recentSignatures = ArrayDeque<String>()
        var pending: List<ChatMessage> = emptyList()
        var step = 0
        var lastUsageTotal = 0L

        outer@ while (true) {
            var hasMoreToolCalls = true
            inner@ while (hasMoreToolCalls || pending.isNotEmpty()) {
                if (pending.isNotEmpty()) {
                    // Surface each queued message so the UI can place it in the timeline; then fold it in.
                    pending.forEach { m ->
                        val text = m.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                        if (text.isNotBlank()) emit(AgentEvent.UserMessage(text))
                    }
                    messages += pending
                    pending = emptyList()
                    recentSignatures.clear() // new user/steering input breaks any apparent doom loop
                }

                step++
                val settings = turnSettings()
                // Resolve mode + tools fresh each turn so a mid-run plan approval (PLAN→BUILD) takes effect.
                val mode = modeProvider()
                val activeTools = visibleTools(mode)
                val toolDefs = activeTools.map { ToolDef(it.name, it.description, it.parameters) }
                // A fresh AgentLoop starts at lastUsageTotal=0, so seed from a size estimate of the
                // accumulated history; otherwise compaction never fires on the first turn and an
                // oversized restored session is sent at full size (context-length-exceeded).
                val seenTokens = maxOf(lastUsageTotal, contextManager.estimatedSize(messages))
                if (contextManager.isOverflow(seenTokens, settings.contextLimit)) {
                    val before = messages.size
                    val compacted = contextManager.compact(settings.model, messages.toList())
                    if (compacted.size < before) {
                        messages.clear()
                        messages.addAll(compacted)
                        lastUsageTotal = 0L
                        emit(AgentEvent.Compacted(messages.size))
                    } else {
                        // Could not reduce the context - surface it instead of silently re-sending an oversized request.
                        emit(AgentEvent.Error("context window exceeded and could not be compacted"))
                        return@flow
                    }
                }
                val lastStep = step >= config.maxSteps
                val system = PromptAssembler.assemble(config, settings.model, activeTools, mode)
                val wire = if (lastStep) {
                    messages + ChatMessage(Role.USER, listOf(MessagePart.Text(MAX_STEPS_REMINDER)))
                } else {
                    messages.toList()
                }
                val request = ChatRequest(
                    model = settings.model,
                    system = system,
                    messages = wire,
                    tools = if (lastStep) emptyList() else toolDefs,
                    reasoningEffort = settings.reasoningEffort,
                    maxTokens = settings.maxOutput?.toInt(),
                    sessionId = config.sessionId,
                )

                val text = StringBuilder()
                val reasoning = StringBuilder()
                val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
                var failure: String? = null

                provider.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> { text.append(event.text); emit(AgentEvent.TextDelta(event.text)) }
                        is StreamEvent.ReasoningDelta -> { reasoning.append(event.text); emit(AgentEvent.ReasoningDelta(event.text)) }
                        is StreamEvent.ToolCallStart ->
                            // Back-fill a synthetic id if a gateway omitted one, so the assistant
                            // ToolCall and its matching ToolResult.callId stay paired on the next turn.
                            toolCalls[event.index] = ToolCallAccumulator(event.id.ifBlank { "call_${event.index}" }, event.name)
                        // A fragment before its ToolCallStart is dropped; the provider guarantees Start precedes args per index.
                        is StreamEvent.ToolCallArgsDelta -> toolCalls[event.index]?.args?.append(event.jsonFragment)
                        is StreamEvent.ToolCallEnd -> Unit
                        is StreamEvent.Usage -> {
                            lastUsageTotal = event.input + event.output +
                                (event.cacheRead ?: 0) + (event.cacheWrite ?: 0) + (event.reasoning ?: 0)
                            emit(AgentEvent.Usage(event.input, event.output))
                        }
                        is StreamEvent.Done -> Unit
                        is StreamEvent.Failed -> failure = event.message
                    }
                }

                // Preserve the conversation so far (prior history + the user's message) on failure, so a
                // dropped connection does not reset context for the next message. The partial/failed assistant
                // turn is intentionally excluded - it is only appended on the next line, after this check.
                failure?.let { emit(AgentEvent.Error(it, messages.toList())); return@flow }
                messages += ChatMessage(Role.ASSISTANT, assistantParts(reasoning, text, toolCalls))

                if (lastStep) {
                    emit(AgentEvent.TurnComplete(messages.toList()))
                    return@flow
                }

                // Gate on the presence of accumulated calls, NOT the stop reason: assistantParts always
                // persists tool_use blocks when toolCalls is non-empty, and every tool_use MUST be answered
                // by a tool_result or the next request is rejected. A non-TOOL_USE stop that still carried
                // tool calls (gateway quirk) would otherwise orphan them. No calls -> nothing to execute.
                if (toolCalls.isNotEmpty()) {
                    if (isDoomLoop(recentSignatures, toolCalls.values)) {
                        if (!context.requestPermission("doom_loop", "repeating the same tool call(s)")) {
                            emit(AgentEvent.Error("stopped: repeated identical tool calls")); return@flow
                        }
                        recentSignatures.clear()
                    }
                    val resultParts = executeBatch(toolCalls.values.toList(), mode)
                    messages += ChatMessage(Role.USER, resultParts)
                    hasMoreToolCalls = true
                } else {
                    hasMoreToolCalls = false
                }
                pending = steering.poll().map(::steeringMessage)
            }

            val followUps = followUp.poll()
            if (followUps.isNotEmpty()) {
                pending = followUps.map { ChatMessage(Role.USER, listOf(MessagePart.Text(it))) }
                continue@outer
            }
            break@outer
        }
        emit(AgentEvent.TurnComplete(messages.toList()))
    }

    /** Emits ToolStarted for the batch, runs it (parallel unless any tool is sequential), then emits ToolFinished in call order. */
    private suspend fun FlowCollector<AgentEvent>.executeBatch(calls: List<ToolCallAccumulator>, mode: AgentMode): List<MessagePart> {
        calls.forEach { emit(AgentEvent.ToolStarted(it.id, it.name, it.args.toString())) }
        // Serialize whenever a tool can prompt for permission (mutating) or opts into sequential,
        // so permission dialogs never race - even if a tool sets sequential=false while mutating.
        val anySequential = calls.any { val t = tools.get(it.name); t?.sequential == true || t?.mutating == true }
        val results = if (anySequential) {
            calls.map { executeOne(it, mode) }
        } else {
            coroutineScope { calls.map { call -> async { executeOne(call, mode) } }.awaitAll() }
        }
        return calls.mapIndexed { i, call ->
            val result = results[i]
            emit(AgentEvent.ToolFinished(call.id, result.output, result.isError))
            MessagePart.ToolResult(call.id, result.output, result.isError)
        }
    }

    private suspend fun executeOne(call: ToolCallAccumulator, mode: AgentMode): ToolResult {
        val tool = tools.get(call.name)
            ?: return ToolResult("unknown tool: ${call.name}", isError = true)
        // Defense-in-depth: PLAN must never mutate, even if a tool was somehow requested.
        if (mode == AgentMode.PLAN && tool.mutating) {
            return ToolResult("blocked: ${tool.name} mutates state and is not allowed in PLAN mode", isError = true)
        }
        if (tool.mutating && !context.requestPermission(tool.name, summarize(call))) {
            return ToolResult("permission denied by user for ${tool.name}", isError = true)
        }
        return try {
            tool.execute(parseArgs(call.args.toString()), context)
        } catch (cancel: CancellationException) {
            throw cancel // never swallow cooperative cancellation
        } catch (t: Throwable) {
            ToolResult("tool '${call.name}' failed: ${t.message}", isError = true)
        }
    }

    /** True when the current batch matches the previous [DOOM_LOOP_THRESHOLD]-1 batches exactly. Mutates [recent]. */
    private fun isDoomLoop(recent: ArrayDeque<String>, calls: Collection<ToolCallAccumulator>): Boolean {
        val signature = calls.joinToString(";") { "${it.name}(${it.args})" }
        recent.addLast(signature)
        if (recent.size > DOOM_LOOP_THRESHOLD) recent.removeFirst()
        return recent.size == DOOM_LOOP_THRESHOLD && recent.all { it == signature }
    }

    private fun assistantParts(
        reasoning: StringBuilder,
        text: StringBuilder,
        toolCalls: Map<Int, ToolCallAccumulator>,
    ): List<MessagePart> = buildList {
        if (reasoning.isNotEmpty()) add(MessagePart.Reasoning(reasoning.toString()))
        if (text.isNotEmpty()) add(MessagePart.Text(text.toString()))
        toolCalls.values.forEach { add(MessagePart.ToolCall(it.id, it.name, it.args.toString())) }
    }

    private fun visibleTools(mode: AgentMode): List<Tool> = when (mode) {
        AgentMode.BUILD -> tools.all().filterNot { it.planOnly }
        AgentMode.PLAN -> tools.all().filterNot { it.mutating }
    }

    private fun parseArgs(json: String): JsonObject =
        if (json.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { argsJson.parseToJsonElement(json).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        }

    private fun summarize(call: ToolCallAccumulator): String = "${call.name}(${call.args.toString().take(200)})"

    // A queued/steering message is folded in as a plain user turn (no wrapper), so the persisted history
    // and the on-screen timeline match exactly; the model treats it as the user's next input.
    private fun steeringMessage(text: String): ChatMessage =
        ChatMessage(Role.USER, listOf(MessagePart.Text(text)))

    private class ToolCallAccumulator(val id: String, val name: String) {
        val args = StringBuilder()
    }

    private companion object {
        const val DOOM_LOOP_THRESHOLD = 3
        const val MAX_STEPS_REMINDER =
            "<system-reminder>Maximum steps reached. Tools are disabled. Respond with text only to summarize and wrap up.</system-reminder>"
    }
}
