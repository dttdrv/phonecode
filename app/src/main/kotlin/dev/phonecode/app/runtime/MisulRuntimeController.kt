package dev.phonecode.app.runtime

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class MisulModel(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Long,
    val outputLimit: Long,
    val reasoning: String? = null,
    val toolCall: Boolean = true,
)

internal data class MisulProvider(
    val id: String,
    val endpoint: String,
    val credential: String,
    val dialect: String,
    val headers: Map<String, String> = emptyMap(),
)

internal data class MisulRuntimeSpec(
    val workspaceRoot: File,
    val stateRoot: File,
    val systemPrompt: String,
    val model: MisulModel,
    val provider: MisulProvider,
    val allowMutatingTools: Boolean = false,
) {
    fun toJson(): JSONObject {
        val selection = JSONObject()
            .put("provider", model.provider)
            .put("model", model.id)
            .put("context_window", model.contextWindow)
            .put("output_limit", model.outputLimit)
        model.reasoning?.let { selection.put("reasoning", it) }

        val profile = JSONObject()
            .put("id", model.id)
            .put("name", model.name)
            .put("provider", model.provider)
            .put("context_window", model.contextWindow)
            .put("output_limit", model.outputLimit)
            .put("max_output_tokens", model.outputLimit)
        model.reasoning?.let { profile.put("reasoning", it) }

        val capability = JSONObject()
            .put("id", model.id)
            .put("context_window", model.contextWindow)
            .put("output_limit", model.outputLimit)
            .put("reasoning", model.reasoning != null)
            .put("tool_call", model.toolCall)
        model.reasoning?.let { capability.put("reasoning_efforts", JSONArray().put(it)) }

        val providerJson = JSONObject()
            .put("id", provider.id)
            .put("endpoint", provider.endpoint.trimEnd('/'))
            .put("credential_ref", provider.credential)
            .put("credential_source", "explicit")
            .put("dialect", provider.dialect)
            .put("headers", JSONArray(provider.headers.map { (name, value) ->
                JSONObject().put("name", name).put("value", value)
            }))
            .put("models", JSONArray().put(capability))
            .put("retry", JSONObject().put("max_retries", 2).put("deadline_ms", 120_000))

        return JSONObject()
            .put("workspace_root", workspaceRoot.absolutePath)
            .put("state_root", stateRoot.absolutePath)
            .put("system_prompt", systemPrompt.ifBlank { "You are Misul, a coding agent running on this Android device." })
            .put("default_selection", selection)
            .put("model_profiles", JSONArray().put(profile))
            .put("provider_configs", JSONArray().put(providerJson))
            .put("maximum_active_runs", 1)
            .put("max_steps", 32)
            .put("allow_mutating_tools", allowMutatingTools)
    }

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(toJson().toString().encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal sealed interface MisulRuntimeEvent {
    data class Text(val delta: String) : MisulRuntimeEvent
    data class Reasoning(val delta: String) : MisulRuntimeEvent
    data class Started(val sessionId: String) : MisulRuntimeEvent
    data class Ended(val sessionId: String, val stopReason: String) : MisulRuntimeEvent
    data class ToolStarted(val id: String, val name: String, val input: String) : MisulRuntimeEvent
    data class ToolFinished(val id: String, val name: String, val isError: Boolean) : MisulRuntimeEvent
    data class ApprovalRequested(val id: String, val name: String, val input: String) : MisulRuntimeEvent
    data class ProtocolError(val message: String) : MisulRuntimeEvent
}

internal data class MisulPromptResult(
    val status: String,
    val content: String,
    val failure: String,
    val providerFailure: MisulProviderFailure? = null,
)

internal data class MisulProviderFailure(
    val category: String,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val providerCode: String? = null,
    val providerType: String? = null,
    val providerParam: String? = null,
    val message: String,
    val retryAfterMillis: Long? = null,
)

internal fun MisulPromptResult.userFacingFailure(): String {
    val detail = providerFailure ?: return when (failure) {
        "provider" -> "The provider request failed. Check your connection and provider settings, then try again."
        "canceled" -> "The message was stopped."
        "max_steps" -> "Misul reached the step limit for this message."
        else -> "Misul stopped this message: $failure."
    }
    val summary = when (detail.category) {
        "authentication" -> "The provider rejected this API key. Check it in Settings > Providers."
        "rate_limited" -> "The provider rate limited this request. Wait a moment, then try again."
        "timeout" -> "The provider request timed out. Check your connection and try again."
        "connection" -> "Misul could not connect to the provider. Check your connection and try again."
        "invalid_request" -> "The provider rejected this request."
        "malformed_response" -> "The provider returned a response Misul could not read."
        "unsupported" -> "This provider or model does not support the requested operation."
        else -> detail.message.ifBlank { "The provider request failed." }
    }
    val references = buildList {
        detail.httpStatus?.let { add(it.toString()) }
        detail.providerCode?.takeIf(String::isNotBlank)?.let(::add)
        detail.requestId?.takeIf(String::isNotBlank)?.let { add("request $it") }
    }
    return if (references.isEmpty()) summary else "$summary (${references.joinToString()})"
}

internal data class ParsedMisulRecord(
    val events: List<MisulRuntimeEvent> = emptyList(),
    val settlement: MisulPromptResult? = null,
    val failure: String? = null,
    val terminal: Boolean = settlement != null || failure != null,
)

internal fun parseMisulRecord(record: String, expectedId: Long): ParsedMisulRecord {
    val json = runCatching { JSONObject(record) }.getOrElse {
        return ParsedMisulRecord(events = listOf(MisulRuntimeEvent.ProtocolError("Malformed Misul runtime record")))
    }
    json.optString("method").takeIf(String::isNotEmpty)?.let { method ->
        val params = json.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "message_delta" -> ParsedMisulRecord(events = buildList {
                params.optString("reasoning_delta").takeIf(String::isNotEmpty)?.let { add(MisulRuntimeEvent.Reasoning(it)) }
                params.optString("text_delta").takeIf(String::isNotEmpty)?.let { add(MisulRuntimeEvent.Text(it)) }
            })
            "agent_start" -> ParsedMisulRecord(events = listOf(MisulRuntimeEvent.Started(params.optString("session_id"))))
            "agent_end" -> ParsedMisulRecord(events = listOf(MisulRuntimeEvent.Ended(
                params.optString("session_id"),
                params.optString("stop_reason"),
            )))
            "tool_start" -> ParsedMisulRecord(events = listOf(MisulRuntimeEvent.ToolStarted(
                id = params.optString("id"),
                name = params.optString("name"),
                input = params.opt("input")?.toString().orEmpty(),
            )))
            "tool_end" -> ParsedMisulRecord(events = listOf(MisulRuntimeEvent.ToolFinished(
                id = params.optString("id"),
                name = params.optString("name"),
                isError = params.optBoolean("is_error"),
            )))
            "approval_request" -> ParsedMisulRecord(events = listOf(MisulRuntimeEvent.ApprovalRequested(
                id = params.optString("id"),
                name = params.optString("name"),
                input = params.opt("input")?.toString().orEmpty(),
            )))
            else -> ParsedMisulRecord()
        }
    }
    if (!json.has("id") || json.optLong("id", Long.MIN_VALUE) != expectedId) return ParsedMisulRecord()
    json.optJSONObject("error")?.let { error ->
        return ParsedMisulRecord(failure = error.optString("message", "Misul runtime request failed"))
    }
    val result = json.optJSONObject("result") ?: return ParsedMisulRecord(failure = "Misul returned an invalid terminal record")
    val providerFailure = result.optJSONObject("provider_failure")?.let { failure ->
        MisulProviderFailure(
            category = failure.optString("category", "provider"),
            httpStatus = failure.optInt("http_status").takeIf { failure.has("http_status") && !failure.isNull("http_status") },
            requestId = failure.optString("request_id").takeIf(String::isNotBlank),
            providerCode = failure.optString("provider_code").takeIf(String::isNotBlank),
            providerType = failure.optString("provider_type").takeIf(String::isNotBlank),
            providerParam = failure.optString("provider_param").takeIf(String::isNotBlank),
            message = failure.optString("redacted_message"),
            retryAfterMillis = failure.optLong("retry_after_ms").takeIf { failure.has("retry_after_ms") && !failure.isNull("retry_after_ms") },
        )
    }
    return ParsedMisulRecord(settlement = MisulPromptResult(
        status = result.optString("status", "failed"),
        content = result.optString("content"),
        failure = result.optString("failure", "internal"),
        providerFailure = providerFailure,
    ))
}

internal class MisulRuntimeController {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "misul-runtime") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val requestIds = AtomicLong(10)
    private var nativeSession: MisulNative.Session? = null
    private var specFingerprint: String? = null

    suspend fun importSessions(spec: MisulRuntimeSpec, sessions: List<MisulImportSession>): Int = withContext(dispatcher) {
        val native = ensureOpen(spec)
        sessions.count { session ->
            request(native, "session/import", JSONObject()
                .put("session_id", session.id)
                .put("model", spec.model.id)
                .put("messages", session.messages))
                .optBoolean("imported")
        }
    }

    suspend fun prompt(
        spec: MisulRuntimeSpec,
        sessionId: String,
        prompt: String,
        onEvent: (MisulRuntimeEvent) -> Unit,
    ): MisulPromptResult = withContext(dispatcher) {
        val native = ensureOpen(spec)
        request(native, "session/start", JSONObject()
            .put("session_id", sessionId)
            .put("cwd", spec.workspaceRoot.absolutePath)
            .put("model", spec.model.id)
            .also { params -> spec.model.reasoning?.let { params.put("thinking", it) } })
        val promptId = requestIds.incrementAndGet()
        val submission = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", promptId)
            .put("method", "session/prompt")
            .put("params", JSONObject().put("prompt", prompt))
        check(native.request(submission.toString().encodeToByteArray()).isEmpty()) {
            "Misul prompt submission returned an unexpected synchronous record"
        }
        while (true) {
            native.nextEvent(EVENT_POLL_MILLIS)?.decodeToString()?.let { raw ->
                val parsed = parseMisulRecord(raw, promptId)
                parsed.events.forEach(onEvent)
                parsed.failure?.let { throw IllegalStateException(it) }
                parsed.settlement?.let { return@withContext it }
            }
            yield()
        }
        error("unreachable")
    }

    suspend fun abort() = withContext(dispatcher) {
        val native = nativeSession ?: return@withContext
        val id = requestIds.incrementAndGet()
        val abort = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "session/abort")
            .put("params", JSONObject())
        check(native.request(abort.toString().encodeToByteArray()).isEmpty())
    }

    suspend fun respondToApproval(id: String, approved: Boolean) = withContext(dispatcher) {
        val native = nativeSession ?: return@withContext
        native.hostResponse(JSONObject()
            .put("type", "approval")
            .put("id", id)
            .put("approved", approved)
            .toString()
            .encodeToByteArray())
    }

    fun close() {
        runBlocking(dispatcher) {
            nativeSession?.close()
            nativeSession = null
            specFingerprint = null
        }
        dispatcher.close()
        executor.shutdown()
    }

    private fun ensureOpen(spec: MisulRuntimeSpec): MisulNative.Session {
        val fingerprint = spec.fingerprint()
        nativeSession?.takeIf { specFingerprint == fingerprint }?.let { return it }
        nativeSession?.close()
        spec.workspaceRoot.mkdirs()
        spec.stateRoot.mkdirs()
        val opened = MisulNative.open(spec.toJson().toString().encodeToByteArray())
        try {
            request(opened, "rpc/handshake", JSONObject()
                .put("protocol_major", 1)
                .put("protocol_minor", 0))
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
        nativeSession = opened
        specFingerprint = fingerprint
        return opened
    }

    private fun request(native: MisulNative.Session, method: String, params: JSONObject): JSONObject {
        val id = requestIds.incrementAndGet()
        val raw = native.request(JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
            .encodeToByteArray())
            .decodeToString()
        val parsed = JSONObject(raw)
        parsed.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "$method failed")) }
        check(parsed.optLong("id", Long.MIN_VALUE) == id && parsed.has("result")) { "Invalid Misul response for $method" }
        return parsed.getJSONObject("result")
    }

    private companion object {
        const val EVENT_POLL_MILLIS = 50
    }
}
