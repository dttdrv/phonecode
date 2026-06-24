package dev.phonecode.tools.shell

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = object : ToolContext {
        override val workspacePath: String get() = tmp.root.absolutePath
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    /** Host shell: the tool's default targets Android's /system/bin/sh; tests use the JVM host's. */
    private fun hostShell(): List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) listOf("cmd.exe", "/c")
        else listOf("/bin/sh", "-c")

    private fun args(command: String, timeoutS: Int? = null) = buildJsonObject {
        put("command", command)
        timeoutS?.let { put("timeout_s", it) }
    }

    @Test fun runsACommandInTheWorkspace() = runBlocking {
        tmp.newFile("hello.txt")
        val result = ShellTool({ hostShell() }).execute(args("dir") , context).let {
            // "dir" works on cmd; on sh use ls
            if (it.isError) ShellTool({ hostShell() }).execute(args("ls"), context) else it
        }
        assertFalse(result.output, result.isError)
        assertTrue(result.output, result.output.contains("hello.txt"))
    }

    @Test fun nonZeroExitIsAnError() = runBlocking {
        val result = ShellTool({ hostShell() }).execute(args("exit 3"), context)
        assertTrue(result.isError)
        assertTrue(result.output, result.output.contains("exit code 3"))
    }

    @Test fun missingCommandIsAnError() = runBlocking {
        val result = ShellTool({ hostShell() }).execute(buildJsonObject { }, context)
        assertTrue(result.isError)
    }

    @Test fun injectedEnvironmentReachesTheProcess() = runBlocking {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val echo = if (isWin) "echo %PC_TEST%" else "echo \$PC_TEST"
        val result = ShellTool({ hostShell() }, { mapOf("PC_TEST" to "phonecode-env") }).execute(args(echo), context)
        assertFalse(result.output, result.isError)
        assertTrue(result.output, result.output.contains("phonecode-env"))
    }

    @Test fun schemaRequiresCommand() {
        val schema = ShellTool().parameters.toString()
        assertTrue(schema, schema.contains("\"required\":[\"command\"]"))
        assertEquals("bash", ShellTool().name)
        assertTrue(ShellTool().mutating)
    }
}
