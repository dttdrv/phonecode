package dev.phonecode.app.runtime

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VmArtifactStoreTest {
    @Test
    fun verifiesAndOpensOnlyTheThreeManifestBoundArtifacts() {
        val fixture = Fixture()

        val metadata = fixture.store.verify()
        val opened = fixture.store.openVerified()

        try {
            assertEquals(listOf("vmlinuz", "initramfs.cpio.gz", "system.img"), metadata.artifacts.map { it.name })
            assertArrayEquals(fixture.kernel, read(opened.kernel))
            assertArrayEquals(fixture.initramfs, read(opened.initramfs))
            assertArrayEquals(fixture.system, read(opened.systemImage))
        } finally {
            opened.close()
        }
    }

    @Test
    fun missingManifestHasAStableFailClosedStatus() {
        val store = VmArtifactStore(
            source = MapSource(emptyMap()),
            cacheDirectory = createTempDirectory("vm-artifacts-missing-").toFile(),
        )

        val error = runCatching { store.verify() }.exceptionOrNull()

        assertTrue(error is VmArtifactException)
        assertEquals(
            "Isolated VM runtime metadata is missing: vm/build-manifest.json.",
            error?.message,
        )
    }

    @Test
    fun malformedOrDuplicateMetadataIsRejectedBeforeArtifactsOpen() {
        val fixture = Fixture()
        val duplicate = fixture.manifest.replace(
            "\"name\":\"system.img\"",
            "\"name\":\"vmlinuz\"",
        )
        val store = fixture.store(mapOf("vm/build-manifest.json" to duplicate.toByteArray()))

        val error = runCatching { store.verify() }.exceptionOrNull()

        assertTrue(error is VmArtifactException)
        assertTrue(error?.message.orEmpty().startsWith("Isolated VM runtime metadata is corrupt:"))
    }

    @Test
    fun exactByteCountAndDigestAreBothEnforced() {
        val fixture = Fixture()
        val wrongSize = fixture.store(
            mapOf("vm/system.img" to (fixture.system + byteArrayOf(0))),
        )
        val wrongDigest = fixture.store(
            mapOf("vm/system.img" to fixture.system.reversedArray()),
        )

        assertEquals(
            "Isolated VM runtime artifact is corrupt: vm/system.img (byte count mismatch).",
            runCatching { wrongSize.verify() }.exceptionOrNull()?.message,
        )
        assertEquals(
            "Isolated VM runtime artifact is corrupt: vm/system.img (SHA-256 mismatch).",
            runCatching { wrongDigest.verify() }.exceptionOrNull()?.message,
        )
    }

    private fun read(descriptor: android.os.ParcelFileDescriptor): ByteArray =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor),
        ).use { it.readBytes() }

    private class Fixture {
        val kernel = "kernel".toByteArray()
        val initramfs = "initramfs".toByteArray()
        val system = "system-image".toByteArray()
        val manifest: String
        private val files: Map<String, ByteArray>
        val store: VmArtifactStore

        init {
            val artifacts = linkedMapOf(
                "vmlinuz" to kernel,
                "initramfs.cpio.gz" to initramfs,
                "system.img" to system,
            )
            val entries = artifacts.entries.joinToString(",") { (name, bytes) ->
                """{"bytes":${bytes.size},"name":"$name","sha256":"${sha256(bytes)}"}"""
            }
            manifest =
                """{"architecture":"aarch64","artifacts":[$entries],"build_epoch":1,"packages_lock_sha256":"${"0".repeat(64)}","protocol_version":1,"schema":"phonecode-guest-build-manifest-v1","sources_lock_sha256":"${"1".repeat(64)}","system_payload":{"bytes":1,"sha256":"${"2".repeat(64)}"},"toolchain_lock_sha256":"${"3".repeat(64)}"}"""
            files = buildMap {
                put("vm/build-manifest.json", manifest.toByteArray())
                artifacts.forEach { (name, bytes) -> put("vm/$name", bytes) }
            }
            store = store()
        }

        fun store(overrides: Map<String, ByteArray> = emptyMap()) = VmArtifactStore(
            source = MapSource(files + overrides),
            cacheDirectory = createTempDirectory("vm-artifacts-").toFile(),
        )
    }

    private class MapSource(
        private val files: Map<String, ByteArray>,
    ) : VmArtifactSource {
        override fun open(path: String) =
            files[path]?.let(::ByteArrayInputStream) ?: throw FileNotFoundException(path)
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
