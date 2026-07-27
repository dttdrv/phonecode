package dev.phonecode.app.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.tools.shell.LocalShellBackend
import dev.phonecode.tools.shell.ShellBackend
import dev.phonecode.tools.shell.ShellBackendStatus
import dev.phonecode.tools.ToolResult
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
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
        assertEquals(
            "Isolated VM runtime metadata is missing: vm/build-manifest.json.",
            status.detail,
        )
        assertTrue(result.isError)
        assertEquals(status.detail, result.output)
    }

    @Test
    fun releaseSelectsIsolatedBackendOnlyAfterArtifactVerification() {
        val files = validArtifactFiles()
        val isolated = StubBackend("verified isolated VM")

        val backend = ShellBackendFactory.createRelease(
            artifactStore = VmArtifactStore(
                source = MapArtifactSource(files),
                cacheDirectory = temporaryDirectory("factory-valid"),
            ),
            isolatedBackendFactory = { isolated },
        )

        assertTrue(backend === isolated)
    }

    @Test
    fun releaseFailsClosedWhenAnArtifactDoesNotMatchMetadata() {
        val files = validArtifactFiles().toMutableMap()
        files["vm/system.img"] = "tampered".toByteArray()

        val backend = ShellBackendFactory.createRelease(
            artifactStore = VmArtifactStore(
                source = MapArtifactSource(files),
                cacheDirectory = temporaryDirectory("factory-corrupt"),
            ),
            isolatedBackendFactory = { error("must not construct isolated backend") },
        )

        val status = backend.status("/workspace")
        assertFalse(status.available)
        assertEquals(
            "Isolated VM runtime artifact is corrupt: vm/system.img (byte count mismatch).",
            status.detail,
        )
    }

    private fun validArtifactFiles(): Map<String, ByteArray> {
        val artifacts = linkedMapOf(
            "vmlinuz" to "kernel".toByteArray(),
            "initramfs.cpio.gz" to "initramfs".toByteArray(),
            "system.img" to "system".toByteArray(),
        )
        val entries = artifacts.entries.joinToString(",") { (name, bytes) ->
            """{"bytes":${bytes.size},"name":"$name","sha256":"${sha256(bytes)}"}"""
        }
        val manifest =
            """{"architecture":"aarch64","artifacts":[$entries],"build_epoch":1,"packages_lock_sha256":"${"0".repeat(64)}","protocol_version":1,"schema":"phonecode-guest-build-manifest-v1","sources_lock_sha256":"${"1".repeat(64)}","system_payload":{"bytes":1,"sha256":"${"2".repeat(64)}"},"toolchain_lock_sha256":"${"3".repeat(64)}"}"""
        return buildMap {
            put("vm/build-manifest.json", manifest.toByteArray())
            artifacts.forEach { (name, bytes) -> put("vm/$name", bytes) }
        }
    }

    private fun temporaryDirectory(name: String) =
        createTempDirectory("phonecode-$name-").toFile().also { it.deleteOnExit() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class MapArtifactSource(
        private val files: Map<String, ByteArray>,
    ) : VmArtifactSource {
        override fun open(path: String) =
            files[path]?.let(::ByteArrayInputStream) ?: throw java.io.FileNotFoundException(path)
    }

    private class StubBackend(
        private val detail: String,
    ) : ShellBackend {
        override fun status(workspacePath: String) = ShellBackendStatus(true, detail)
        override suspend fun execute(command: String, workspacePath: String, timeoutSeconds: Int) =
            ToolResult(detail)
        override suspend fun start(command: String, workspacePath: String) = ToolResult(detail)
        override fun list(workspacePath: String?) = ToolResult(detail)
        override fun output(sessionId: String, workspacePath: String?, maxChars: Int) =
            ToolResult(detail)
        override suspend fun input(
            sessionId: String,
            data: String,
            appendNewline: Boolean,
            workspacePath: String?,
        ) = ToolResult(detail)
        override suspend fun stop(sessionId: String, workspacePath: String?) = ToolResult(detail)
        override suspend fun stopWorkspace(workspacePath: String) = Unit
        override fun stopAll() = Unit
    }
}
