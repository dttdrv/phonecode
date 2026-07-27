package dev.phonecode.app.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.tools.shell.LocalShellBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShellBackendFactoryTest {
    @Test
    fun debugPreservesThePrototypeBackendBoundary() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()

        assertTrue(ShellBackendFactory.create(app, debugRuntimeEnabled = true) is LocalShellBackend)
    }

    @Test
    fun releaseNeverCreatesThePrototypeBackend() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val backend = ShellBackendFactory.create(app, debugRuntimeEnabled = false)

        val status = backend.status(app.filesDir.absolutePath)
        val result = backend.execute("echo unsafe", app.filesDir.absolutePath, 60)

        assertFalse(status.available)
        assertEquals("The isolated VM runtime is not available in this release build.", status.detail)
        assertTrue(result.isError)
        assertEquals(status.detail, result.output)
    }
}
