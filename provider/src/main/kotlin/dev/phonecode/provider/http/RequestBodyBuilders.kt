package dev.phonecode.provider.http

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.ToolDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Serializes a provider-agnostic [ChatRequest] into each wire format's request body.
 * Split out from the providers so the mapping is unit-testable without HTTP.
 * `Reasoning` parts are not replayed (Anthropic requires signed thinking blocks).
 */
object RequestBodyBuilders {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Drops messages with no wire content (e.g. assistant turns with only reasoning - both APIs reject empty
     * content), then coalesces consecutive same-role messages. A stopped or interrupted turn can leave two
     * user turns in a row (the assistant never replied); Anthropic rejects that outright ("roles must
     * alternate between user and assistant"), and it read to the user as the model losing context. Merging
     * the parts is lossless - the model still sees every block, just in one turn.
     */
    private fun wireMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val nonEmpty = messages.filter { m -> m.parts.any { it is MessagePart.Text || it is MessagePart.ToolCall || it is MessagePart.ToolResult } }
        val merged = ArrayList<ChatMessage>(nonEmpty.size)
        for (m in nonEmpty) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role) merged[merged.lastIndex] = last.copy(parts = last.parts + m.parts)
            else merged.add(m)
        }
        return merged
    }

    // ---- OpenAI-compatible ----

    fun toOpenAiBody(req: ChatRequest): String = buildJsonObject {
        put("model", req.model)
        req.sessionId?.let { put("prompt_cache_key", it) } // OpenAI-family automatic prefix caching
        put("messages", openAiMessages(req))
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        if (req.tools.isNotEmpty()) put("tools", openAiTools(req.tools))
        if (req.reasoningEffort != ReasoningEffort.DEFAULT) {
            put("reasoning_effort", req.reasoningEffort.name.lowercase())
        }
        req.maxTokens?.let { put("max_completion_tokens", it) }
    }.toString()

    private fun openAiMessages(req: ChatRequest): JsonArray = buildJsonArray {
        req.system?.let { sys -> addJsonObject { put("role", "system"); put("content", sys) } }
        for (msg in wireMessages(req.messages)) {
            when (msg.role) {
                Role.USER -> {
                    msg.parts.filterIsInstance<MessagePart.ToolResult>().forEach { tr ->
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tr.callId)
                            put("content", tr.content)
                        }
                    }
                    val text = msg.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                    val hasToolResult = msg.parts.any { it is MessagePart.ToolResult }
                    if (text.isNotEmpty() || !hasToolResult) {
                        addJsonObject { put("role", "user"); put("content", text) }
                    }
                }
                Role.ASSISTANT -> {
                    val text = msg.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                    val toolCalls = msg.parts.filterIsInstance<MessagePart.ToolCall>()
                    addJsonObject {
                        put("role", "assistant")
                        if (text.isNotEmpty()) put("content", text) else put("content", JsonNull)
                        if (toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                toolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.argsJson)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openAiTools(tools: List<ToolDef>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parametersJsonSchema)
                }
            }
        }
    }

    // ---- Anthropic ----

    fun toAnthropicBody(req: ChatRequest): String = buildJsonObject {
        put("model", req.model)
        val (maxTokens, thinking) = anthropicTokensAndThinking(req)
        put("max_tokens", maxTokens)
        req.system?.let { sys ->
            // System as one cache-controlled text block keeps the large static prefix in the cache.
            put("system", buildJsonArray {
                addJsonObject {
                    put("type", "text")
                    put("text", sys)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            })
        }
        put("messages", anthropicMessages(req))
        put("stream", true)
        if (req.tools.isNotEmpty()) put("tools", anthropicTools(req.tools))
        thinking?.let { put("thinking", it) }
    }.toString()

    private fun anthropicTokensAndThinking(req: ChatRequest): Pair<Int, JsonObject?> {
        if (req.reasoningEffort == ReasoningEffort.DEFAULT) return (req.maxTokens ?: 4096) to null
        val budget = when (req.reasoningEffort) {
            ReasoningEffort.LOW -> 2048
            ReasoningEffort.MEDIUM -> 8192
            ReasoningEffort.HIGH -> 16000
            ReasoningEffort.DEFAULT -> 0
        }
        val maxTokens = maxOf(req.maxTokens ?: 0, budget + 8192)
        val thinking = buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) }
        return maxTokens to thinking
    }

    private fun anthropicMessages(req: ChatRequest): JsonArray {
        val msgs = wireMessages(req.messages)
        val count = msgs.size
        return buildJsonArray {
            msgs.forEachIndexed { index, msg ->
                // The last two non-system messages get a cache breakpoint; they "walk forward"
                // each turn so the prior tail becomes the next turn's cache_read prefix.
                val cachePoint = index >= count - 2
                addJsonObject {
                    put("role", if (msg.role == Role.USER) "user" else "assistant")
                    putJsonArray("content") {
                        val blocks = msg.parts.filter { it !is MessagePart.Reasoning } // reasoning not replayed
                        blocks.forEachIndexed { blockIndex, part ->
                            val markCache = cachePoint && blockIndex == blocks.lastIndex
                            addJsonObject {
                                when (part) {
                                    is MessagePart.Text -> { put("type", "text"); put("text", part.text) }
                                    is MessagePart.ToolCall -> {
                                        put("type", "tool_use")
                                        put("id", part.id)
                                        put("name", part.name)
                                        put("input", parseInput(part.argsJson))
                                    }
                                    is MessagePart.ToolResult -> {
                                        put("type", "tool_result")
                                        put("tool_use_id", part.callId)
                                        put("content", part.content)
                                        if (part.isError) put("is_error", true)
                                    }
                                    is MessagePart.Reasoning -> Unit // filtered out above
                                }
                                if (markCache) putJsonObject("cache_control") { put("type", "ephemeral") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun anthropicTools(tools: List<ToolDef>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            addJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", t.parametersJsonSchema)
            }
        }
    }

    private fun parseInput(argsJson: String): JsonObject =
        if (argsJson.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                lenient.parseToJsonElement(argsJson).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
        }

    // ---- OpenAI Responses API (ChatGPT "Codex" backend) ----

    /**
     * Serializes a request for OpenAI's Responses API, which the ChatGPT/Codex backend speaks (it does NOT
     * accept Chat Completions). Differences from [toOpenAiBody]: the system prompt is `instructions`; the
     * conversation is a flat `input` list of typed items (one per message part) - `message` with
     * input_text/output_text, `function_call`, and `function_call_output` - and tools are bare
     * `{type:function, name, parameters}` (not nested under `function`).
     */
    fun toResponsesBody(req: ChatRequest): String = buildJsonObject {
        put("model", req.model)
        req.system?.let { put("instructions", it) }
        putJsonArray("input") {
            for (msg in req.messages) {
                for (part in msg.parts) {
                    when (part) {
                        is MessagePart.Text -> addJsonObject {
                            put("type", "message")
                            put("role", if (msg.role == Role.USER) "user" else "assistant")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", if (msg.role == Role.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                }
                            }
                        }
                        is MessagePart.ToolCall -> addJsonObject {
                            put("type", "function_call")
                            put("call_id", part.id)
                            put("name", part.name)
                            put("arguments", part.argsJson.ifBlank { "{}" })
                        }
                        is MessagePart.ToolResult -> addJsonObject {
                            put("type", "function_call_output")
                            put("call_id", part.callId)
                            put("output", part.content)
                        }
                        is MessagePart.Reasoning -> Unit // not replayed
                    }
                }
            }
        }
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parametersJsonSchema)
                    }
                }
            }
            put("parallel_tool_calls", true)
        }
        put("stream", true)
        put("store", false)
        if (req.reasoningEffort != ReasoningEffort.DEFAULT) {
            putJsonObject("reasoning") {
                put("effort", req.reasoningEffort.name.lowercase())
                put("summary", "auto")
            }
        }
        // NOTE: no max_output_tokens. The ChatGPT/Codex backend rejects it ("unsupported parameter") -
        // output length is governed by the model's reasoning effort + verbosity, not a token cap.
    }.toString()
}
