package dev.phonecode.provider.http

/** A dispatched SSE event: an optional `event:` type and the joined `data:` payload. */
data class RawSse(val event: String?, val data: String)

/**
 * Incremental SSE line parser - one code path for production streaming and tests.
 * Feed lines without their terminators; a blank line dispatches the buffered
 * event. Multi-line `data:` fields are joined with '\n' (SSE spec); `:` comment
 * lines and other fields (id, retry) are ignored.
 */
class SseParser {
    private val data = StringBuilder()
    private var event: String? = null

    /** Returns a [RawSse] when [line] completes an event (a blank line), else null. */
    fun line(line: String): RawSse? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field: String
        val value: String
        if (colon == -1) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1).removePrefix(" ")
        }
        when (field) {
            "data" -> data.append(value).append('\n')
            "event" -> event = value
            else -> Unit // id, retry, unknown fields
        }
        return null
    }

    /** Dispatch any buffered event not yet terminated by a blank line. */
    fun flush(): RawSse? = dispatch()

    private fun dispatch(): RawSse? {
        if (data.isEmpty() && event == null) return null
        val payload = if (data.isNotEmpty()) data.toString().removeSuffix("\n") else ""
        val ev = RawSse(event, payload)
        data.setLength(0)
        event = null
        return ev
    }

    companion object {
        /** Parse full SSE text into its dispatched events (test helper / non-streaming use). */
        fun parseAll(text: String): List<RawSse> {
            val parser = SseParser()
            val out = mutableListOf<RawSse>()
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            for (line in normalized.split('\n')) {
                parser.line(line)?.let(out::add)
            }
            parser.flush()?.let(out::add)
            return out
        }
    }
}
