package dev.phonecode.tools.external

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ExternalDirectoryToolTest {

    private val tool = ExternalDirectoryTool()

    /** A ToolContext whose permission gate is fixed to [allow] and records what it was asked about. */
    private class FakeCtx(private val allow: Boolean) : ToolContext {
        override val workspacePath = "/some/workspace"
        var asked: Pair<String, String>? = null
        override suspend fun requestPermission(tool: String, summary: String): Boolean {
            asked = tool to summary
            return allow
        }
    }

    // A temp file deliberately OUTSIDE any workspace.
    private lateinit var external: File

    @Before fun setUp() {
        external = File.createTempFile("phonecode-external", ".txt").apply { writeText("secret payload") }
    }

    @After fun tearDown() {
        external.delete()
    }

    private fun args(path: String): JsonObject = buildJsonObject { put("path", path) }

    @Test fun allowedReadSucceeds() = runBlocking {
        val ctx = FakeCtx(allow = true)
        val result = tool.execute(args(external.absolutePath), ctx)
        assertFalse(result.isError)
        assertEquals("secret payload", result.output)
        // It gated on the absolute path before reading.
        assertEquals(external.absolutePath, ctx.asked?.second)
        assertEquals("external_directory", ctx.asked?.first)
    }

    @Test fun deniedReadReturnsErrorWithoutReading() = runBlocking {
        val ctx = FakeCtx(allow = false)
        val result = tool.execute(args(external.absolutePath), ctx)
        assertTrue(result.isError)
        // The file contents must not leak when permission is denied.
        assertFalse(result.output.contains("secret payload"))
        // It did ask before refusing.
        assertEquals(external.absolutePath, ctx.asked?.second)
    }

    @Test fun listsDirectory() = runBlocking {
        val dir = external.parentFile
        val result = tool.execute(args(dir.absolutePath), FakeCtx(allow = true))
        assertFalse(result.isError)
        assertTrue(result.output.contains(external.name))
    }

    @Test fun relativePathIsRejectedWithoutAsking() = runBlocking {
        val ctx = FakeCtx(allow = true)
        val result = tool.execute(args("relative/path.txt"), ctx)
        assertTrue(result.isError)
        assertTrue(result.output.contains("absolute"))
        // A relative path is rejected up front - the permission gate is never consulted.
        assertEquals(null, ctx.asked)
    }

    @Test fun missingPathIsError() = runBlocking {
        assertTrue(tool.execute(JsonObject(emptyMap()), FakeCtx(allow = true)).isError)
    }

    @Test fun capsOutputToMaxBytes() = runBlocking {
        external.writeText("0123456789")
        val result = tool.execute(
            buildJsonObject { put("path", external.absolutePath); put("maxBytes", 4) },
            FakeCtx(allow = true),
        )
        assertFalse(result.isError)
        assertTrue(result.output.startsWith("0123"))
        assertFalse(result.output.contains("456789"))
    }
}
