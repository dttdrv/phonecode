package dev.phonecode.app.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VmGuestProtocolTest {
    @Test
    fun encodingUsesBigEndianLengthAndCanonicalJson() {
        val frame = buildJsonObject {
            put("v", 1)
            put("type", "exit")
            put("truncated", false)
            put("status", 0)
            put("id", 7)
        }

        val encoded = VmGuestProtocol.encode(frame)
        val payload = encoded.copyOfRange(VmGuestProtocol.HEADER_BYTES, encoded.size)

        assertEquals(payload.size, readLength(encoded))
        assertEquals(
            """{"id":7,"status":0,"truncated":false,"type":"exit","v":1}""",
            payload.toString(Charsets.UTF_8),
        )
        assertEquals(frame, VmGuestProtocol.read(ByteArrayInputStream(encoded)))
    }

    @Test
    fun cleanEndOfStreamReturnsNull() {
        assertNull(VmGuestProtocol.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun truncatedHeaderAndPayloadFailClosed() {
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, '{'.code.toByte())))
        }
    }

    @Test
    fun oversizedAndZeroLengthFramesFailClosed() {
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(ByteArrayInputStream(lengthPrefix(0)))
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(
                ByteArrayInputStream(lengthPrefix(VmGuestProtocol.MAX_FRAME_PAYLOAD_BYTES + 1)),
            )
        }
    }

    @Test
    fun malformedUtf8AndNonCanonicalJsonFailClosed() {
        val malformedUtf8 = framed(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(ByteArrayInputStream(malformedUtf8))
        }

        val nonCanonical = """{"v":1,"type":"shutdown","id":0}""".toByteArray()
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.read(ByteArrayInputStream(framed(nonCanonical)))
        }
    }

    @Test
    fun unknownTypesWrongVersionsAndInvalidRequestIdsFailClosed() {
        listOf(
            buildJsonObject { put("id", 1); put("type", "future"); put("v", 1) },
            buildJsonObject { put("id", 1); put("type", "exec"); put("v", 2) },
            buildJsonObject {
                put("background", false)
                put("command", "true")
                put("cwd", "/workspace")
                put("id", 0)
                put("timeout_ms", 1_000)
                put("type", "exec")
                put("v", 1)
            },
            buildJsonObject { put("id", 1); put("type", "shutdown"); put("v", 1) },
        ).forEach { invalid ->
            assertThrows(VmProtocolException::class.java) {
                VmGuestProtocol.read(ByteArrayInputStream(VmGuestProtocol.encodeCanonicalUnchecked(invalid)))
            }
        }
    }

    @Test
    fun readyRequiresExactNonceAgentAndCapabilities() {
        val nonce = "ab".repeat(32)
        val ready = readyFrame(nonce)

        val parsed = VmGuestProtocol.validateReady(ready, nonce)

        assertEquals("phonecode-guestd", parsed.agent)
        assertEquals(VmGuestProtocol.REQUIRED_CAPABILITIES, parsed.capabilities)
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateReady(ready, "cd".repeat(32))
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateReady(readyFrame("NOT-HEX"), "NOT-HEX")
        }
    }

    @Test
    fun requestTrackerRejectsZeroAndDuplicateLiveIdsButAllowsReuseAfterClose() {
        val tracker = VmRequestIds()

        assertThrows(VmProtocolException::class.java) { tracker.open(0) }
        tracker.open(41)
        assertTrue(tracker.isOpen(41))
        assertThrows(VmProtocolException::class.java) { tracker.open(41) }
        tracker.close(41)
        assertFalse(tracker.isOpen(41))
        tracker.open(41)
    }

    @Test
    fun execValidationPinsWorkspaceCommandAndTimeoutBounds() {
        val valid = execFrame("printf ready", VmGuestProtocol.MAX_COMMAND_BYTES)
        assertEquals("printf ready", VmGuestProtocol.validateExec(valid).command)

        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateExec(execFrame("", VmGuestProtocol.MAX_COMMAND_BYTES))
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateExec(execFrame("x".repeat(VmGuestProtocol.MAX_COMMAND_BYTES + 1), Int.MAX_VALUE))
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateExec(
                execFrame("true", VmGuestProtocol.MAX_COMMAND_BYTES, cwd = "/"),
            )
        }
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateExec(
                execFrame("true", VmGuestProtocol.MAX_COMMAND_BYTES, timeoutMillis = 1_800_001),
            )
        }
    }

    @Test
    fun outputValidationEnforcesDecodedChunkLimitAndCanonicalBase64() {
        val maximum = ByteArray(VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES) { (it % 251).toByte() }
        val valid = outputFrame(maximum)

        assertArrayEquals(maximum, VmGuestProtocol.validateOutput(valid).bytes)
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateOutput(outputFrame(ByteArray(VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES + 1)))
        }
        val nonCanonicalBase64 = JsonObject(valid + ("data_b64" to JsonPrimitive("YQ")))
        assertThrows(VmProtocolException::class.java) {
            VmGuestProtocol.validateOutput(nonCanonicalBase64)
        }
    }

    @Test
    fun outputWindowRetainsOnlyTheNewestBoundedBytes() {
        val window = VmOutputWindow()
        val first = ByteArray(24_000) { 1 }
        val second = ByteArray(24_000) { 2 }
        val third = ByteArray(12_000) { 3 }

        first.asList().chunked(VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES).forEach {
            window.append(it.toByteArray())
        }
        second.asList().chunked(VmGuestProtocol.MAX_OUTPUT_CHUNK_BYTES).forEach {
            window.append(it.toByteArray())
        }
        window.append(third)

        assertTrue(window.truncated)
        assertEquals(VmGuestProtocol.MAX_RETAINED_OUTPUT_BYTES, window.bytes().size)
        assertArrayEquals(ByteArray(12_000) { 2 } + third, window.bytes().takeLast(24_000).toByteArray())
    }

    @Test
    fun writerAndReaderHandleBackToBackFrames() {
        val stream = ByteArrayOutputStream()
        VmGuestProtocol.write(stream, buildJsonObject { put("id", 0); put("type", "shutdown"); put("v", 1) })
        VmGuestProtocol.write(stream, readyFrame("12".repeat(32)))
        val input = ByteArrayInputStream(stream.toByteArray())

        assertEquals("shutdown", VmGuestProtocol.read(input)?.get("type")?.jsonPrimitive?.content)
        assertEquals("ready", VmGuestProtocol.read(input)?.get("type")?.jsonPrimitive?.content)
        assertNull(VmGuestProtocol.read(input))
    }

    private fun readyFrame(nonce: String) = buildJsonObject {
        put("agent", "phonecode-guestd")
        put("capabilities", buildJsonArray {
            VmGuestProtocol.REQUIRED_CAPABILITIES.forEach { add(JsonPrimitive(it)) }
        })
        put("id", 0)
        put("nonce", nonce)
        put("type", "ready")
        put("v", 1)
    }

    private fun execFrame(
        command: String,
        @Suppress("UNUSED_PARAMETER") commandLimit: Int,
        cwd: String = "/workspace",
        timeoutMillis: Int = 60_000,
    ) = buildJsonObject {
        put("background", false)
        put("command", command)
        put("cwd", cwd)
        put("id", 41)
        put("timeout_ms", timeoutMillis)
        put("type", "exec")
        put("v", 1)
    }

    private fun outputFrame(bytes: ByteArray) = buildJsonObject {
        put("data_b64", Base64.getEncoder().encodeToString(bytes))
        put("id", 41)
        put("seq", 0)
        put("type", "output")
        put("v", 1)
    }

    private fun readLength(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xff) shl 24) or
            ((frame[1].toInt() and 0xff) shl 16) or
            ((frame[2].toInt() and 0xff) shl 8) or
            (frame[3].toInt() and 0xff)

    private fun lengthPrefix(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    private fun framed(payload: ByteArray): ByteArray = lengthPrefix(payload.size) + payload
}
