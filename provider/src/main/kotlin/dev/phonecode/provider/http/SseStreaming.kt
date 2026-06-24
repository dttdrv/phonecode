package dev.phonecode.provider.http

import dev.phonecode.provider.domain.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/** A stateful, per-stream mapper from raw SSE events to normalized [StreamEvent]s. */
internal interface SseStreamMapper {
    fun map(raw: RawSse): List<StreamEvent>
    fun finish(): List<StreamEvent>
}

private const val MAX_ERROR_BODY = 2048L

/**
 * The shared streaming path for every provider: execute the request, frame the
 * SSE body one line at a time through [SseParser], run each event through
 * [mapper], then flush. Errors are surfaced as [StreamEvent.Failed]; the flow
 * always completes. Providers differ only in URL, request body, and mapper.
 */
internal fun streamSse(
    client: OkHttpClient,
    request: Request,
    mapper: SseStreamMapper,
): Flow<StreamEvent> = flow {
    val parser = SseParser()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            // peekBody bounds the read - never buffer a whole hostile error body just to keep 500 chars.
            val detail = response.peekBody(MAX_ERROR_BODY).string().take(500)
            emit(StreamEvent.Failed("HTTP ${response.code}: $detail"))
            return@use
        }
        val source = response.body?.source()
        if (source == null) {
            emit(StreamEvent.Failed("empty response body"))
            return@use
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            parser.line(line)?.let { raw -> mapper.map(raw).forEach { emit(it) } }
        }
        parser.flush()?.let { raw -> mapper.map(raw).forEach { emit(it) } }
        mapper.finish().forEach { emit(it) }
    }
}.flowOn(Dispatchers.IO).catch { e -> emit(StreamEvent.Failed(e.message ?: "stream error")) }
