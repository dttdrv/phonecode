package dev.phonecode.provider.http

import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.provider.preset.ProviderPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Streams from OpenAI's Responses API as spoken by the ChatGPT/Codex backend ("Sign in with ChatGPT").
 * Auth is the OAuth access token (Bearer) plus a `chatgpt-account-id` header, both injected through the
 * preset. The request body and SSE event shape differ from Chat Completions, so this has its own builder
 * ([RequestBodyBuilders.toResponsesBody]) and mapper ([ResponsesStreamMapper]).
 */
class CodexProvider(
    private val preset: ProviderPreset,
    private val apiKey: String,
    private val client: OkHttpClient,
) : LlmProvider {
    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        val httpRequest = Request.Builder()
            .url("${preset.baseUrl.trimEnd('/')}/responses")
            .post(RequestBodyBuilders.toResponsesBody(request).toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .applyAuth(preset, apiKey)
            .build()
        return streamSse(client, httpRequest, ResponsesStreamMapper())
    }
}

/**
 * Maps Responses API SSE events to normalized [StreamEvent]s. Text arrives via `response.output_text.delta`,
 * reasoning via `response.reasoning_summary_text.delta`; tool calls are items - `response.output_item.added`
 * announces a function_call (id + call_id + name), `response.function_call_arguments.delta` streams its
 * arguments, `response.output_item.done` closes it. Tool calls are keyed by a string item_id on the wire;
 * each is assigned a stable integer index for the [StreamEvent] model. [StreamEvent.Done] is deferred to
 * [finish] so [StreamEvent.Usage] (from `response.completed`) precedes it.
 */
internal class ResponsesStreamMapper : SseStreamMapper {
    private val indexByItem = HashMap<String, Int>()
    private val argsStreamed = HashSet<String>()
    private var nextIndex = 0
    private var stopReason = StopReason.END_TURN
    private var terminated = false

    override fun map(raw: RawSse): List<StreamEvent> {
        if (terminated) return emptyList()
        val data = raw.data.trim()
        if (data.isEmpty() || data == "[DONE]") return emptyList()
        val obj = try {
            streamJson.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            terminated = true
            return listOf(StreamEvent.Failed("codex parse error: ${e.message}"))
        }
        // The event type is on the SSE `event:` line and mirrored in the data's `type` field; prefer data.
        val type = obj.str("type") ?: raw.event ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        when (type) {
            "response.output_text.delta" ->
                obj.str("delta")?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.TextDelta(it)) }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                obj.str("delta")?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.ReasoningDelta(it)) }
            "response.output_item.added" -> obj.obj("item")?.takeIf { it.str("type") == "function_call" }?.let { item ->
                val itemId = item.str("id") ?: item.str("call_id") ?: return out
                val index = indexByItem.getOrPut(itemId) { nextIndex++ }
                stopReason = StopReason.TOOL_USE
                out.add(StreamEvent.ToolCallStart(index, item.str("call_id") ?: itemId, item.str("name") ?: ""))
            }
            "response.function_call_arguments.delta" -> {
                val itemId = obj.str("item_id") ?: return out
                val index = indexByItem[itemId] ?: return out
                obj.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                    argsStreamed.add(itemId)
                    out.add(StreamEvent.ToolCallArgsDelta(index, it))
                }
            }
            "response.output_item.done" -> obj.obj("item")?.takeIf { it.str("type") == "function_call" }?.let { item ->
                val itemId = item.str("id") ?: item.str("call_id") ?: return out
                val index = indexByItem.getOrPut(itemId) { nextIndex++ }
                // Some responses deliver the arguments only here (no deltas) - emit them whole in that case.
                if (itemId !in argsStreamed) {
                    item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { out.add(StreamEvent.ToolCallArgsDelta(index, it)) }
                }
                out.add(StreamEvent.ToolCallEnd(index))
            }
            "response.completed" -> obj.obj("response")?.obj("usage")?.let { out.add(parseUsage(it)) }
            "response.failed" -> {
                terminated = true
                out.add(StreamEvent.Failed(obj.obj("response")?.obj("error")?.str("message") ?: "codex request failed"))
            }
            "error" -> {
                terminated = true
                out.add(StreamEvent.Failed(obj.str("message") ?: obj.obj("error")?.str("message") ?: "codex error"))
            }
        }
        return out
    }

    override fun finish(): List<StreamEvent> {
        if (terminated) return emptyList()
        return listOf(StreamEvent.Done(stopReason))
    }

    private fun parseUsage(usage: JsonObject): StreamEvent.Usage {
        val input = usage.longOf("input_tokens") ?: 0
        val output = usage.longOf("output_tokens") ?: 0
        val cacheRead = usage.obj("input_tokens_details")?.longOf("cached_tokens")
        val reasoning = usage.obj("output_tokens_details")?.longOf("reasoning_tokens")
        // input_tokens includes cached; subtract so `input` is the fresh prompt, matching the OpenAI mapper.
        return StreamEvent.Usage(
            input = (input - (cacheRead ?: 0)).coerceAtLeast(0),
            output = output,
            cacheRead = cacheRead,
            reasoning = reasoning,
        )
    }
}
