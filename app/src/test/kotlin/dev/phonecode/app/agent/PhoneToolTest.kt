package dev.phonecode.app.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneToolTest {

    private val tool = PhoneTool(ApplicationProvider.getApplicationContext<Application>())
    private val context = object : ToolContext {
        override val workspacePath: String get() = "/"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private fun args(action: String, value: String? = null, level: Int? = null) = buildJsonObject {
        put("action", action)
        value?.let { put("value", it) }
        level?.let { put("level", it) }
    }

    @Test fun clipboardRoundTrips() = runBlocking {
        val set = tool.execute(args("clipboard_set", "hello from the agent"), context)
        assertFalse(set.output, set.isError)
        val get = tool.execute(args("clipboard_get"), context)
        assertEquals("hello from the agent", get.output)
    }

    @Test fun deviceInfoReportsTheBasics() = runBlocking {
        val result = tool.execute(args("device_info"), context)
        assertFalse(result.output, result.isError)
        assertTrue(result.output, result.output.contains("battery:"))
        assertTrue(result.output, result.output.contains("storage:"))
    }

    @Test fun unknownActionAndMissingArgsFailCleanly() = runBlocking {
        assertTrue(tool.execute(args("teleport"), context).isError)
        assertTrue(tool.execute(args("open_app"), context).isError)
        assertTrue(tool.execute(args("open_url", "ftp://nope"), context).isError)
        assertTrue(tool.execute(args("settings_panel", "warp"), context).isError)
        assertTrue(tool.execute(buildJsonObject { }, context).isError)
    }

    @Test fun openAppSuggestsWhenNotFound() = runBlocking {
        val result = tool.execute(args("open_app", "definitely-not-installed-xyz"), context)
        assertTrue(result.isError)
        assertTrue(result.output, result.output.contains("not found"))
    }

    @Test fun everyActionPassesThePermissionGate() {
        assertTrue(tool.mutating) // the loop prompts the user before every phone action
        assertEquals("phone", tool.name)
    }
}
