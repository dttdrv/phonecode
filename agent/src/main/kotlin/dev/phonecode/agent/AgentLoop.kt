package dev.phonecode.agent

import dev.phonecode.agent.prompt.PromptAssembler
import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.FailureKind
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
import kotlinx.coroutines.delay
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
    private val toolProvider: suspend () -> ToolRegistry = { tools },
    private val mcpInstructionsProvider: suspend () -> List<String> = { config.mcpInstructions },
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
                val stepTools = toolProvider().snapshot()
                val activeTools = visibleTools(mode, stepTools)
                val toolDefs = activeTools.map { ToolDef(it.name, it.description, it.parameters) }
                val lastStep = step >= config.maxSteps
                val requestTools = if (lastStep) emptyList() else toolDefs
                val system = PromptAssembler.assemble(
                    config.copy(mcpInstructions = mcpInstructionsProvider()),
                    settings.model,
                    activeTools,
                    mode,
                )
                if (contextManager.isOverflow(contextManager.estimatedFixedSize(system, requestTools), settings.contextLimit)) {
                    emit(AgentEvent.Error("system prompt and enabled tool definitions exceed this model's context window"))
                    return@flow
                }
                // A fresh AgentLoop starts at lastUsageTotal=0, so seed from a size estimate of the
                // accumulated history; otherwise compaction never fires on the first turn and an
                // oversized restored session is sent at full size (context-length-exceeded).
                fun requestMessages(): List<ChatMessage> = if (lastStep) {
                    messages + ChatMessage(Role.USER, listOf(MessagePart.Text(MAX_STEPS_REMINDER)))
                } else {
                    messages
                }
                val seenTokens = maxOf(
                    lastUsageTotal,
                    contextManager.estimatedRequestSize(system, requestMessages(), requestTools),
                )
                if (contextManager.isOverflow(seenTokens, settings.contextLimit)) {
                    val before = messages.size
                    val compacted = contextManager.compact(settings.model, messages.toList())
                    if (compacted.size < before) {
                        messages.clear()
                        messages.addAll(compacted)
                        lastUsageTotal = 0L
                        if (contextManager.isOverflow(
                                contextManager.estimatedRequestSize(system, requestMessages(), requestTools),
                                settings.contextLimit,
                            )
                        ) {
                            emit(AgentEvent.Error("context window remains too large after compaction"))
                            return@flow
                        }
                    } else {
                        // Could not reduce the context - surface it instead of silently re-sending an oversized request.
                        emit(AgentEvent.Error("context window exceeded and could not be compacted"))
                        return@flow
                    }
                }
                val wire = requestMessages()
                val request = ChatRequest(
                    model = settings.model,
                    system = system,
                    messages = wire,
                    tools = requestTools,
                    reasoningEffort = settings.reasoningEffort,
                    maxTokens = settings.maxOutput?.toInt(),
                    sessionId = config.sessionId,
                )

                val text = StringBuilder()
                val reasoning = StringBuilder()
                val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
                var failure: StreamEvent.Failed? = null
                var emittedContent = false
                var attempt = 0
                var streamedChars = 0L

                fun reserveStreamChars(length: Int) {
                    if (streamedChars + length > MAX_STREAMED_TURN_CHARS) throw StreamLimitExceeded()
                    streamedChars += length
                }

                while (true) {
                    failure = null
                    try {
                        provider.stream(request).collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> {
                                    reserveStreamChars(event.text.length)
                                    emittedContent = true
                                    text.append(event.text)
                                    emit(AgentEvent.TextDelta(event.text))
                                }
                                is StreamEvent.ReasoningDelta -> {
                                    reserveStreamChars(event.text.length)
                                    emittedContent = true
                                    reasoning.append(event.text)
                                    emit(AgentEvent.ReasoningDelta(event.text))
                                }
                                is StreamEvent.ToolCallStart -> {
                                    reserveStreamChars(event.id.length)
                                    reserveStreamChars(event.name.length)
                                    if (!toolCalls.containsKey(event.index) && toolCalls.size >= MAX_TOOL_CALLS_PER_TURN) {
                                        throw StreamLimitExceeded()
                                    }
                                    emittedContent = true
                                    toolCalls[event.index] = ToolCallAccumulator(event.id.ifBlank { "call_${event.index}" }, event.name)
                                }
                                is StreamEvent.ToolCallArgsDelta -> {
                                    reserveStreamChars(event.jsonFragment.length)
                                    emittedContent = true
                                    toolCalls[event.index]?.args?.append(event.jsonFragment)
                                }
                                is StreamEvent.ToolCallEnd -> emittedContent = true
                                is StreamEvent.Usage -> {
                                    emittedContent = true
                                    lastUsageTotal = event.input + event.output +
                                        (event.cacheRead ?: 0) + (event.cacheWrite ?: 0) + (event.reasoning ?: 0)
                                    emit(AgentEvent.Usage(event.input, event.output))
                                }
                                is StreamEvent.Done -> Unit
                                is StreamEvent.Failed -> failure = event
                            }
                        }
                    } catch (_: StreamLimitExceeded) {
                        failure = StreamEvent.Failed(STREAM_LIMIT_MESSAGE, kind = FailureKind.INVALID_REQUEST)
                    }
                    val current = failure ?: break
                    if (!current.retryable || emittedContent || attempt >= MAX_RETRIES) break
                    attempt++
                    val wait = retryDelay(attempt, current.retryAfterMillis)
                    emit(AgentEvent.Retrying(attempt, current.message))
                    delay(wait)
                }

                // Preserve the conversation so far (prior history + the user's message) on failure, so a
                // dropped connection does not reset context for the next message. The partial/failed assistant
                // turn is intentionally excluded - it is only appended on the next line, after this check.
                failure?.let {
                    val preserved = if (text.isNotEmpty() || reasoning.isNotEmpty()) {
                        messages + ChatMessage(Role.ASSISTANT, assistantParts(reasoning, text, emptyMap()))
                    } else {
                        messages.toList()
                    }
                    emit(AgentEvent.Error(it.message, preserved, it.kind, it.statusCode, it.retryAfterMillis, it.code))
                    return@flow
                }
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
                    emit(AgentEvent.HistoryCheckpoint(messages.toList()))
                    if (isDoomLoop(recentSignatures, toolCalls.values)) {
                        if (!context.requestPermission("doom_loop", "repeating the same tool call(s)")) {
                            val stopped = messages + ChatMessage(
                                Role.USER,
                                toolCalls.values.map {
                                    MessagePart.ToolResult(it.id, "stopped: repeated identical tool calls", isError = true)
                                },
                            )
                            emit(AgentEvent.Error("stopped: repeated identical tool calls", stopped))
                            return@flow
                        }
                        recentSignatures.clear()
                    }
                    val resultParts = executeBatch(toolCalls.values.toList(), mode, stepTools)
                    messages += ChatMessage(Role.USER, resultParts)
                    emit(AgentEvent.HistoryCheckpoint(messages.toList()))
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

    /** Runs the batch (parallel unless any tool is sequential), then emits ToolFinished in call order. */
    private suspend fun FlowCollector<AgentEvent>.executeBatch(
        calls: List<ToolCallAccumulator>,
        mode: AgentMode,
        registry: ToolRegistry,
    ): List<MessagePart> {
        // Serialize whenever a tool can prompt for permission (mutating) or opts into sequential,
        // so permission dialogs never race - even if a tool sets sequential=false while mutating.
        val anySequential = calls.any {
            val tool = registry.get(it.name)
            tool?.sequential == true || tool?.mutates(parseArgs(it.args.toString())) == true
        }
        val results = if (anySequential) {
            calls.map { call ->
                val argsJson = call.args.toString()
                val tool = registry.get(call.name)
                val mutates = tool?.mutates(parseArgs(argsJson)) == true
                if (mutates && mode != AgentMode.PLAN) {
                    emit(AgentEvent.ToolAwaitingApproval(call.id, call.name, argsJson))
                } else {
                    emit(AgentEvent.ToolStarted(call.id, call.name, argsJson))
                }
                executeOne(call, mode, registry) {
                    if (mutates && mode != AgentMode.PLAN) {
                        emit(AgentEvent.ToolStarted(call.id, call.name, argsJson))
                    }
                }
            }
        } else {
            calls.forEach { emit(AgentEvent.ToolStarted(it.id, it.name, it.args.toString())) }
            coroutineScope { calls.map { call -> async { executeOne(call, mode, registry) } }.awaitAll() }
        }
        return calls.mapIndexed { i, call ->
            val result = results[i]
            emit(AgentEvent.ToolFinished(call.id, result.output, result.isError))
            MessagePart.ToolResult(call.id, result.output, result.isError)
        }
    }

    private suspend fun executeOne(
        call: ToolCallAccumulator,
        mode: AgentMode,
        registry: ToolRegistry,
        onPermissionGranted: suspend () -> Unit = {},
    ): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult("unknown tool: ${call.name}", isError = true)
        val args = parseArgs(call.args.toString())
        // Defense-in-depth: PLAN must never mutate, even if a tool was somehow requested.
        if (mode == AgentMode.PLAN && tool.mutates(args)) {
            return ToolResult("blocked: ${tool.name} mutates state and is not allowed in PLAN mode", isError = true)
        }
        if (tool.mutates(args) && !context.requestPermission(tool.name, summarize(call))) {
            return ToolResult("permission denied by user for ${tool.name}", isError = true)
        }
        onPermissionGranted()
        return try {
            tool.execute(args, context).limitOutput()
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

    private fun visibleTools(mode: AgentMode, registry: ToolRegistry): List<Tool> = when (mode) {
        AgentMode.BUILD -> registry.all().filterNot { it.planOnly }
        AgentMode.PLAN -> registry.all().filterNot { it.mutating }
    }

    private fun parseArgs(json: String): JsonObject =
        if (json.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { argsJson.parseToJsonElement(json).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        }

    private fun summarize(call: ToolCallAccumulator): String {
        val raw = call.args.toString()
        // Keep the complete payload available to the approval UI. The UI may collapse long details for
        // readability, but approval must never hide paths or destructive operations from the middle.
        return "${call.name}\n$raw"
    }

    private fun ToolResult.limitOutput(): ToolResult = if (output.length <= MAX_TOOL_OUTPUT_CHARS) {
        this
    } else {
        copy(output = output.take(MAX_TOOL_OUTPUT_CHARS) + "\n[Tool output truncated by PhoneCode. Request a narrower result.]")
    }

    // A queued/steering message is folded in as a plain user turn (no wrapper), so the persisted history
    // and the on-screen timeline match exactly; the model treats it as the user's next input.
    private fun steeringMessage(text: String): ChatMessage =
        ChatMessage(Role.USER, listOf(MessagePart.Text(text)))

    private class ToolCallAccumulator(val id: String, val name: String) {
        val args = StringBuilder()
    }

    private class StreamLimitExceeded : RuntimeException()

    private companion object {
        const val DOOM_LOOP_THRESHOLD = 3
        const val MAX_RETRIES = 5
        const val MAX_STREAMED_TURN_CHARS = 2_000_000L
        const val MAX_TOOL_CALLS_PER_TURN = 128
        const val MAX_TOOL_OUTPUT_CHARS = 64_000
        const val STREAM_LIMIT_MESSAGE =
            "Provider response exceeded PhoneCode's per-turn stream safety limit. Try again or switch models."
        const val MAX_STEPS_REMINDER =
            "PhoneCode has ended tool access for this turn. Respond with text only to summarize the result and remaining work."
    }
}

private fun retryDelay(attempt: Int, requested: Long?): Long =
    (requested ?: (2_000L * (1L shl (attempt - 1)))).coerceIn(0L, 30_000L)
