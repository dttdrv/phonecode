package dev.phonecode.tools.files

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileToolsTest {

    private lateinit var workspace: File
    private lateinit var ctx: ToolContext

    @Before fun setup() {
        workspace = File.createTempFile("phonecode-ws", "").apply { delete(); mkdirs() }
        ctx = object : ToolContext {
            override val workspacePath = workspace.absolutePath
            override suspend fun requestPermission(tool: String, summary: String) = true
        }
    }

    @After fun teardown() {
        workspace.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test fun writeThenRead() = runTest {
        val write = WriteTool().execute(args("path" to "a.txt", "content" to "hello\nworld"), ctx)
        assertFalse(write.isError)
        assertEquals("hello\nworld", File(workspace, "a.txt").readText())
        val read = ReadTool().execute(args("path" to "a.txt"), ctx)
        assertFalse(read.isError)
        assertTrue(read.output.contains("1\thello"))
        assertTrue(read.output.contains("2\tworld"))
    }

    @Test fun editReplacesUniqueText() = runTest {
        File(workspace, "b.kt").writeText("val x = 1\nval y = 2\n")
        val req = buildJsonObject {
            put("path", "b.kt")
            putJsonArray("edits") {
                add(buildJsonObject { put("oldText", "val x = 1"); put("newText", "val x = 42") })
            }
        }
        assertFalse(EditTool().execute(req, ctx).isError)
        assertEquals("val x = 42\nval y = 2\n", File(workspace, "b.kt").readText())
    }

    @Test fun editRejectsNonUniqueText() = runTest {
        File(workspace, "c.kt").writeText("a\na\n")
        val req = buildJsonObject {
            put("path", "c.kt")
            putJsonArray("edits") { add(buildJsonObject { put("oldText", "a"); put("newText", "b") }) }
        }
        val res = EditTool().execute(req, ctx)
        assertTrue(res.isError)
        assertTrue(res.output.contains("not unique"))
    }

    @Test fun editAutoRepairsLegacyTopLevelShape() = runTest {
        File(workspace, "d.txt").writeText("foo")
        val res = EditTool().execute(args("path" to "d.txt", "oldText" to "foo", "newText" to "bar"), ctx)
        assertFalse(res.isError)
        assertEquals("bar", File(workspace, "d.txt").readText())
    }

    @Test fun lsListsEntriesDirectoriesFirst() = runTest {
        File(workspace, "sub").mkdirs()
        File(workspace, "f.txt").writeText("x")
        val res = LsTool().execute(buildJsonObject {}, ctx)
        assertTrue(res.output.contains("sub/"))
        assertTrue(res.output.contains("f.txt"))
    }

    @Test fun globFindsKotlinFiles() = runTest {
        File(workspace, "src").mkdirs()
        File(workspace, "src/Main.kt").writeText("x")
        File(workspace, "src/Util.kt").writeText("y")
        File(workspace, "readme.md").writeText("z")
        val res = GlobTool().execute(args("pattern" to "**/*.kt"), ctx)
        assertTrue(res.output.contains("src/Main.kt"))
        assertTrue(res.output.contains("src/Util.kt"))
        assertFalse(res.output.contains("readme.md"))
    }

    @Test fun grepFindsMatchesWithLineNumbers() = runTest {
        File(workspace, "a.kt").writeText("fun foo() {}\nval bar = 1\n")
        val res = GrepTool().execute(args("pattern" to "fun \\w+"), ctx)
        assertTrue(res.output.contains("a.kt:1:"))
        assertTrue(res.output.contains("fun foo"))
    }

    @Test fun pathEscapeIsRejected() = runTest {
        val res = ReadTool().execute(args("path" to "../outside.txt"), ctx)
        assertTrue(res.isError)
        assertTrue(res.output.contains("escapes the workspace"))
    }

    @Test fun grepRespectsPathGlobFilter() = runTest {
        File(workspace, "src").mkdirs()
        File(workspace, "src/Main.kt").writeText("fun main() {}\n")
        File(workspace, "notes.md").writeText("fun in markdown\n")
        val res = GrepTool().execute(args("pattern" to "fun ", "glob" to "**/*.kt"), ctx)
        assertTrue(res.output.contains("src/Main.kt"))
        assertFalse(res.output.contains("notes.md"))
    }

    @Test fun writeReturnsErrorWhenParentIsAFile() = runTest {
        File(workspace, "afile").writeText("x") // a regular file, not a directory
        val res = WriteTool().execute(args("path" to "afile/child.txt", "content" to "y"), ctx)
        assertTrue(res.isError) // a clean error, not an uncaught exception
    }
}
