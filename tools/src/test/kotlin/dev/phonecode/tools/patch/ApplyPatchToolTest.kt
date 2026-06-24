package dev.phonecode.tools.patch

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
import java.nio.file.Files

class ApplyPatchToolTest {

    private lateinit var workspace: File
    private val tool = ApplyPatchTool()

    private inner class Ctx : ToolContext {
        override val workspacePath get() = workspace.absolutePath
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    @Before fun setUp() {
        workspace = Files.createTempDirectory("apply-patch-test").toFile()
    }

    @After fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun file(name: String) = File(workspace, name)

    private fun patchArgs(patch: String): JsonObject = buildJsonObject { put("patch", patch) }

    @Test fun addsANewFile() = runBlocking {
        val patch = """
            *** Begin Patch
            *** Add File: greeting.txt
            +hello
            +world
            *** End Patch
        """.trimIndent()

        val result = tool.execute(patchArgs(patch), Ctx())

        assertFalse(result.output, result.isError)
        assertTrue(result.output.contains("1 added"))
        assertEquals("hello\nworld\n", file("greeting.txt").readText())
    }

    @Test fun updatesAFileWithAHunk() = runBlocking {
        file("app.kt").writeText("fun main() {\n    println(\"hi\")\n}\n")
        val patch = """
            *** Begin Patch
            *** Update File: app.kt
            @@
             fun main() {
            -    println("hi")
            +    println("bye")
             }
            *** End Patch
        """.trimIndent()

        val result = tool.execute(patchArgs(patch), Ctx())

        assertFalse(result.output, result.isError)
        assertTrue(result.output.contains("1 updated"))
        assertEquals("fun main() {\n    println(\"bye\")\n}\n", file("app.kt").readText())
    }

    @Test fun deletesAFile() = runBlocking {
        file("obsolete.txt").writeText("remove me\n")
        val patch = """
            *** Begin Patch
            *** Delete File: obsolete.txt
            *** End Patch
        """.trimIndent()

        val result = tool.execute(patchArgs(patch), Ctx())

        assertFalse(result.output, result.isError)
        assertTrue(result.output.contains("1 deleted"))
        assertFalse(file("obsolete.txt").exists())
    }

    @Test fun appliesMultipleOpsAtomically() = runBlocking {
        file("keep.txt").writeText("line one\nline two\n")
        file("gone.txt").writeText("bye\n")
        val patch = """
            *** Begin Patch
            *** Add File: new.txt
            +fresh
            *** Update File: keep.txt
            @@
             line one
            -line two
            +line 2
            *** Delete File: gone.txt
            *** End Patch
        """.trimIndent()

        val result = tool.execute(patchArgs(patch), Ctx())

        assertFalse(result.output, result.isError)
        assertEquals("fresh\n", file("new.txt").readText())
        assertEquals("line one\nline 2\n", file("keep.txt").readText())
        assertFalse(file("gone.txt").exists())
    }

    @Test fun mismatchAbortsAllOps() = runBlocking {
        file("target.txt").writeText("real content\n")
        file("victim.txt").writeText("still here\n")
        // The Add and Delete are valid, but the Update's context does not match, so nothing should change.
        val patch = """
            *** Begin Patch
            *** Add File: should-not-exist.txt
            +nope
            *** Update File: target.txt
            @@
             this context is wrong
            -real content
            +new content
            *** Delete File: victim.txt
            *** End Patch
        """.trimIndent()

        val result = tool.execute(patchArgs(patch), Ctx())

        assertTrue(result.isError)
        assertFalse(file("should-not-exist.txt").exists()) // add rolled back
        assertEquals("real content\n", file("target.txt").readText()) // update untouched
        assertTrue(file("victim.txt").exists()) // delete not performed
    }

    @Test fun malformedEnvelopeIsErrorAndChangesNothing() = runBlocking {
        file("data.txt").writeText("original\n")
        val patch = "*** Add File: x.txt\n+y\n" // missing Begin/End wrapper

        val result = tool.execute(patchArgs(patch), Ctx())

        assertTrue(result.isError)
        assertFalse(file("x.txt").exists())
        assertEquals("original\n", file("data.txt").readText())
    }

    @Test fun missingPatchArgIsError() = runBlocking {
        assertTrue(tool.execute(JsonObject(emptyMap()), Ctx()).isError)
    }
}
