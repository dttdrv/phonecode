package dev.phonecode.app.runtime

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal open class VmProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal data class VmReady(
    val agent: String,
    val capabilities: List<String>,
)

internal data class VmExec(
    val id: Long,
    val command: String,
    val timeoutMillis: Int,
    val background: Boolean,
)

internal data class VmOutput(
    val id: Long,
    val sequence: Int,
    val bytes: ByteArray,
)

internal object VmGuestProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 4
    const val MAX_FRAME_PAYLOAD_BYTES = 65_536
    const val MAX_COMMAND_BYTES = 32_768
    const val MAX_OUTPUT_CHUNK_BYTES = 12_000
    const val MAX_RETAINED_OUTPUT_BYTES = 48_000
    const val MAX_TIMEOUT_MILLIS = 1_800_000
    val REQUIRED_CAPABILITIES = listOf("exec", "shutdown", "signal", "stdin")

    private val json = Json {
        allowSpecialFloatingPointValues = false
        explicitNulls = true
        isLenient = false
    }
    private val noncePattern = Regex("[0-9a-f]{64}")
    private val errorCodePattern = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val requestTypes = setOf("exec", "started", "output", "exit", "stdin", "signal")
    private val lifecycleTypes = setOf("hello", "ready", "shutdown")
    private val knownTypes = requestTypes + lifecycleTypes + "error"

    fun encode(frame: JsonObject): ByteArray {
        validateFrame(frame)
        return encodeCanonicalUnchecked(frame)
    }

    fun encodeCanonicalUnchecked(frame: JsonObject): ByteArray {
        val payload = canonicalBytes(frame)
        protocolRequire(payload.isNotEmpty() && payload.size <= MAX_FRAME_PAYLOAD_BYTES) {
            "VM frame payload length is outside the protocol limit"
        }
        return lengthPrefix(payload.size) + payload
    }

    fun write(output: OutputStream, frame: JsonObject) {
        output.write(encode(frame))
    }

    fun hello(nonce: String): JsonObject = buildJsonObject {
        put("id", 0)
        put("nonce", nonce)
        put("type", "hello")
        put("v", VERSION)
    }

    fun exec(
        id: Long,
        command: String,
        timeoutMillis: Int,
        background: Boolean,
    ): JsonObject = buildJsonObject {
        put("background", background)
        put("command", command)
        put("cwd", "/workspace")
        put("id", id)
        put("timeout_ms", timeoutMillis)
        put("type", "exec")
        put("v", VERSION)
    }

    fun stdin(id: Long, bytes: ByteArray, eof: Boolean): JsonObject = buildJsonObject {
        put("data_b64", Base64.getEncoder().encodeToString(bytes))
        put("eof", eof)
        put("id", id)
        put("type", "stdin")
        put("v", VERSION)
    }

    fun signal(id: Long, signal: String): JsonObject = buildJsonObject {
        put("id", id)
        put("signal", signal)
        put("type", "signal")
        put("v", VERSION)
    }

    fun shutdown(): JsonObject = buildJsonObject {
        put("id", 0)
        put("type", "shutdown")
        put("v", VERSION)
    }

    fun read(input: InputStream): JsonObject? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(HEADER_BYTES)
        header[0] = first.toByte()
        readFully(input, header, 1, HEADER_BYTES - 1, "truncated VM frame header")
        val length =
            ((header[0].toLong() and 0xff) shl 24) or
                ((header[1].toLong() and 0xff) shl 16) or
                ((header[2].toLong() and 0xff) shl 8) or
                (header[3].toLong() and 0xff)
        protocolRequire(length in 1..MAX_FRAME_PAYLOAD_BYTES.toLong()) {
            "VM frame payload length is outside the protocol limit"
        }
        val payload = ByteArray(length.toInt())
        readFully(input, payload, 0, payload.size, "truncated VM frame payload")
        val text = decodeUtf8(payload)
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw VmProtocolException("VM frame is not valid JSON", it) }
        val frame = element as? JsonObject
            ?: throw VmProtocolException("VM frame must be a JSON object")
        protocolRequire(payload.contentEquals(canonicalBytes(frame))) {
            "VM frame JSON is not canonical"
        }
        validateFrame(frame)
        return frame
    }

    fun validateReady(frame: JsonObject, expectedNonce: String): VmReady {
        protocolRequire(expectedNonce.matches(noncePattern)) { "VM READY expected nonce is invalid" }
        validateReadyShape(frame)
        protocolRequire(frame.string("nonce") == expectedNonce) { "VM READY nonce mismatch" }
        val capabilities = frame.stringArray("capabilities")
        protocolRequire(capabilities == REQUIRED_CAPABILITIES) { "VM READY capabilities mismatch" }
        return VmReady(
            agent = frame.string("agent"),
            capabilities = capabilities,
        )
    }

    fun validateExec(frame: JsonObject): VmExec {
        requireFields(
            frame,
            setOf("background", "command", "cwd", "id", "timeout_ms", "type", "v"),
        )
        requireEnvelope(frame, "exec")
        val command = frame.string("command")
        protocolRequire(command.isNotEmpty() && command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "VM exec command is outside the protocol limit"
        }
        protocolRequire('\u0000' !in command) { "VM exec command contains NUL" }
        protocolRequire(frame.string("cwd") == "/workspace") { "VM exec cwd must be /workspace" }
        val timeout = frame.int("timeout_ms")
        protocolRequire(timeout in 1..MAX_TIMEOUT_MILLIS) { "VM exec timeout is outside the protocol limit" }
        return VmExec(
            id = frame.long("id"),
            command = command,
            timeoutMillis = timeout,
            background = frame.boolean("background"),
        )
    }

    fun validateOutput(frame: JsonObject): VmOutput {
        requireFields(frame, setOf("data_b64", "id", "seq", "type", "v"))
        requireEnvelope(frame, "output")
        val encoded = frame.string("data_b64")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw VmProtocolException("VM output is not valid Base64", it) }
        protocolRequire(Base64.getEncoder().encodeToString(bytes) == encoded) {
            "VM output Base64 is not canonical"
        }
        protocolRequire(bytes.size <= MAX_OUTPUT_CHUNK_BYTES) {
            "VM output chunk exceeds the protocol limit"
        }
        val sequence = frame.int("seq")
        protocolRequire(sequence >= 0) { "VM output sequence must be non-negative" }
        return VmOutput(frame.long("id"), sequence, bytes)
    }

    private fun validateFrame(frame: JsonObject) {
        val type = frame.string("type")
        protocolRequire(type in knownTypes) { "Unsupported VM frame type: $type" }
        when (type) {
            "hello" -> {
                requireFields(frame, setOf("id", "nonce", "type", "v"))
                requireEnvelope(frame, type)
                protocolRequire(frame.string("nonce").matches(noncePattern)) { "VM hello nonce is invalid" }
            }
            "ready" -> validateReadyShape(frame)
            "exec" -> validateExec(frame)
            "started" -> {
                requireFields(frame, setOf("id", "pid", "type", "v"))
                requireEnvelope(frame, type)
                protocolRequire(frame.int("pid") > 0) { "VM started pid must be positive" }
            }
            "output" -> validateOutput(frame)
            "exit" -> {
                requireFields(frame, setOf("id", "status", "truncated", "type", "v"))
                requireEnvelope(frame, type)
                protocolRequire(frame.int("status") in 0..255) { "VM exit status is outside the protocol limit" }
                frame.boolean("truncated")
            }
            "stdin" -> {
                requireFields(frame, setOf("data_b64", "eof", "id", "type", "v"))
                requireEnvelope(frame, type)
                decodeBoundedBase64(frame.string("data_b64"), "VM stdin")
                frame.boolean("eof")
            }
            "signal" -> {
                requireFields(frame, setOf("id", "signal", "type", "v"))
                requireEnvelope(frame, type)
                protocolRequire(frame.string("signal") in setOf("INT", "KILL", "TERM")) {
                    "VM signal is unsupported"
                }
            }
            "shutdown" -> {
                requireFields(frame, setOf("id", "type", "v"))
                requireEnvelope(frame, type)
            }
            "error" -> {
                requireFields(frame, setOf("code", "id", "message", "type", "v"))
                requireEnvelope(frame, type)
                protocolRequire(frame.string("code").matches(errorCodePattern)) { "VM error code is invalid" }
                protocolRequire(frame.string("message").toByteArray(Charsets.UTF_8).size <= 1_024) {
                    "VM error message exceeds the protocol limit"
                }
            }
        }
    }

    private fun validateReadyShape(frame: JsonObject) {
        requireFields(frame, setOf("agent", "capabilities", "id", "nonce", "type", "v"))
        requireEnvelope(frame, "ready")
        protocolRequire(frame.string("agent") == "phonecode-guestd") { "VM READY agent mismatch" }
        protocolRequire(frame.string("nonce").matches(noncePattern)) { "VM READY nonce is invalid" }
        protocolRequire(frame.stringArray("capabilities") == REQUIRED_CAPABILITIES) {
            "VM READY capabilities mismatch"
        }
    }

    private fun requireEnvelope(frame: JsonObject, expectedType: String) {
        protocolRequire(frame.int("v") == VERSION) { "Unsupported VM protocol version" }
        protocolRequire(frame.string("type") == expectedType) { "VM frame type mismatch" }
        val id = frame.long("id")
        when {
            expectedType in lifecycleTypes -> protocolRequire(id == 0L) {
                "VM lifecycle frame id must be zero"
            }
            expectedType == "error" -> protocolRequire(id >= 0L) {
                "VM error frame id must be non-negative"
            }
            else -> protocolRequire(id > 0L) { "VM request id must be positive" }
        }
    }

    private fun requireFields(frame: JsonObject, fields: Set<String>) {
        protocolRequire(frame.keys == fields) {
            "VM ${frame["type"]?.toString() ?: "unknown"} frame fields do not match protocol v1"
        }
    }

    private fun decodeBoundedBase64(encoded: String, label: String): ByteArray {
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw VmProtocolException("$label is not valid Base64", it) }
        protocolRequire(Base64.getEncoder().encodeToString(bytes) == encoded) {
            "$label Base64 is not canonical"
        }
        protocolRequire(bytes.size <= MAX_OUTPUT_CHUNK_BYTES) { "$label exceeds the protocol limit" }
        return bytes
    }

    private fun canonicalBytes(frame: JsonObject): ByteArray =
        json.encodeToString(JsonElement.serializer(), canonicalize(frame)).toByteArray(Charsets.UTF_8)

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun decodeUtf8(payload: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }.getOrElse { throw VmProtocolException("VM frame is not valid UTF-8", it) }

    private fun readFully(
        input: InputStream,
        output: ByteArray,
        start: Int,
        count: Int,
        error: String,
    ) {
        var offset = start
        val end = start + count
        while (offset < end) {
            val read = input.read(output, offset, end - offset)
            if (read < 0) throw VmProtocolException(error)
            if (read == 0) continue
            offset += read
        }
    }

    private fun lengthPrefix(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    private fun JsonObject.string(name: String): String {
        val primitive = this[name] as? JsonPrimitive
        protocolRequire(primitive?.isString == true) { "VM frame field '$name' must be a string" }
        return requireNotNull(primitive).content
    }

    private fun JsonObject.int(name: String): Int {
        val primitive = this[name] as? JsonPrimitive
        val value = primitive?.intOrNull
        protocolRequire(value != null) { "VM frame field '$name' must be an integer" }
        return requireNotNull(value)
    }

    private fun JsonObject.long(name: String): Long {
        val primitive = this[name] as? JsonPrimitive
        val value = primitive?.longOrNull
        protocolRequire(value != null) { "VM frame field '$name' must be an integer" }
        return requireNotNull(value)
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val primitive = this[name] as? JsonPrimitive
        val value = primitive?.booleanOrNull
        protocolRequire(value != null) { "VM frame field '$name' must be a boolean" }
        return requireNotNull(value)
    }

    private fun JsonObject.stringArray(name: String): List<String> {
        val array = this[name] as? JsonArray
        protocolRequire(array != null) { "VM frame field '$name' must be an array" }
        return requireNotNull(array).mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
            protocolRequire(primitive?.isString == true) {
                "VM frame field '$name[$index]' must be a string"
            }
            requireNotNull(primitive).content
        }
    }

    private inline fun protocolRequire(condition: Boolean, message: () -> String) {
        if (!condition) throw VmProtocolException(message())
    }
}

internal class VmRequestIds {
    private val live = mutableSetOf<Long>()

    @Synchronized
    fun open(id: Long) {
        if (id <= 0) throw VmProtocolException("VM request id must be positive")
        if (!live.add(id)) throw VmProtocolException("VM request id is already active")
    }

    @Synchronized
    fun close(id: Long) {
        if (!live.remove(id)) throw VmProtocolException("VM request id is not active")
    }

    @Synchronized
    fun isOpen(id: Long): Boolean = id in live
}

internal class VmOutputWindow {
    private var retained = ByteArray(0)
    var truncated: Boolean = false
        private set

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.size > VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES) {
            throw VmProtocolException("VM output chunk exceeds the protocol limit")
        }
        val combined = retained + bytes
        if (combined.size > VmGuestProtocol.MAX_RETAINED_OUTPUT_BYTES) {
            retained = combined.copyOfRange(
                combined.size - VmGuestProtocol.MAX_RETAINED_OUTPUT_BYTES,
                combined.size,
            )
            truncated = true
        } else {
            retained = combined
        }
    }

    @Synchronized
    fun bytes(): ByteArray = retained.copyOf()
}
