package dev.phonecode.app.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class VmArtifactException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun interface VmArtifactSource {
    @Throws(IOException::class)
    fun open(path: String): InputStream
}

internal data class VmArtifactMetadata(
    val artifacts: List<VmArtifactMetadataEntry>,
)

internal data class VmArtifactMetadataEntry(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

internal class VmArtifacts(
    val kernel: ParcelFileDescriptor,
    val initramfs: ParcelFileDescriptor,
    val systemImage: ParcelFileDescriptor,
) : Closeable {
    override fun close() {
        runCatching { kernel.close() }
        runCatching { initramfs.close() }
        runCatching { systemImage.close() }
    }
}

/**
 * Authenticates packaged guest bytes against the reproducible guest build manifest.
 *
 * APK assets are streams and may share an offset inside the APK. QEMU needs independent seekable
 * descriptors, so verified bytes are staged into app-private, read-only files and reopened
 * read-only. Every VM launch rechecks both the signed-package source and any cached copy.
 */
internal class VmArtifactStore(
    private val source: VmArtifactSource,
    private val cacheDirectory: File,
) {
    fun verify(): VmArtifactMetadata {
        val metadata = readMetadata()
        metadata.artifacts.forEach { verifySource(it) }
        return metadata
    }

    fun openVerified(): VmArtifacts {
        val metadata = readMetadata()
        val files = metadata.artifacts.associate { artifact ->
            verifySource(artifact)
            artifact.name to stage(artifact)
        }
        val opened = mutableListOf<ParcelFileDescriptor>()
        return try {
            fun open(name: String) =
                ParcelFileDescriptor.open(
                    requireNotNull(files[name]),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).also(opened::add)
            VmArtifacts(
                kernel = open(KERNEL),
                initramfs = open(INITRAMFS),
                systemImage = open(SYSTEM_IMAGE),
            ).also { opened.clear() }
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    private fun readMetadata(): VmArtifactMetadata {
        val bytes = try {
            source.open(MANIFEST_PATH).use { input ->
                input.readBounded(MAX_MANIFEST_BYTES, MANIFEST_PATH)
            }
        } catch (error: FileNotFoundException) {
            throw VmArtifactException("Isolated VM runtime metadata is missing: $MANIFEST_PATH.")
        } catch (error: IOException) {
            throw VmArtifactException(
                "Isolated VM runtime metadata could not be read: $MANIFEST_PATH.",
                error,
            )
        }
        return try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            requireExactKeys(root, MANIFEST_FIELDS)
            require(root.string("schema") == MANIFEST_SCHEMA)
            require(root.int("protocol_version") == VmGuestProtocol.VERSION)
            require(root.string("architecture") == ARCHITECTURE)
            require(root.long("build_epoch") >= 0)
            requireSha256(root.string("packages_lock_sha256"))
            requireSha256(root.string("sources_lock_sha256"))
            requireSha256(root.string("toolchain_lock_sha256"))
            val systemPayload = root.getValue("system_payload").jsonObject
            requireExactKeys(systemPayload, SYSTEM_PAYLOAD_FIELDS)
            require(systemPayload.long("bytes") > 0)
            requireSha256(systemPayload.string("sha256"))

            val entries = root.getValue("artifacts").jsonArray.map { element ->
                val artifact = element.jsonObject
                requireExactKeys(artifact, ARTIFACT_FIELDS)
                VmArtifactMetadataEntry(
                    name = artifact.string("name"),
                    bytes = artifact.long("bytes").also { require(it > 0) },
                    sha256 = artifact.string("sha256").also(::requireSha256),
                )
            }
            require(entries.size == REQUIRED_ARTIFACTS.size)
            require(entries.map { it.name }.toSet() == REQUIRED_ARTIFACTS.toSet())
            require(entries.map { it.name }.distinct().size == entries.size)
            VmArtifactMetadata(
                REQUIRED_ARTIFACTS.map { name -> entries.single { it.name == name } },
            )
        } catch (error: Throwable) {
            if (error is VmArtifactException) throw error
            throw VmArtifactException(
                "Isolated VM runtime metadata is corrupt: $MANIFEST_PATH.",
                error,
            )
        }
    }

    private fun verifySource(artifact: VmArtifactMetadataEntry) {
        val path = "$ASSET_DIRECTORY/${artifact.name}"
        try {
            source.open(path).use { input -> verify(input, artifact, path) }
        } catch (error: FileNotFoundException) {
            throw VmArtifactException("Isolated VM runtime artifact is missing: $path.")
        } catch (error: VmArtifactException) {
            throw error
        } catch (error: IOException) {
            throw VmArtifactException("Isolated VM runtime artifact could not be read: $path.", error)
        }
    }

    private fun stage(artifact: VmArtifactMetadataEntry): File {
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create isolated VM artifact cache"
        }
        val destination = File(cacheDirectory, artifact.name)
        if (destination.isFile && runCatching {
                destination.inputStream().use { verify(it, artifact, artifact.name) }
            }.isSuccess
        ) {
            destination.setReadOnly()
            return destination
        }

        val temporary = File(cacheDirectory, ".${artifact.name}.${System.nanoTime()}.tmp")
        try {
            source.open("$ASSET_DIRECTORY/${artifact.name}").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            temporary.inputStream().use { verify(it, artifact, artifact.name) }
            temporary.setReadOnly()
            runCatching {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            destination.setReadOnly()
            destination.inputStream().use { verify(it, artifact, artifact.name) }
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun verify(
        input: InputStream,
        artifact: VmArtifactMetadataEntry,
        path: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > artifact.bytes) {
                corrupt(path, "byte count mismatch")
            }
            digest.update(buffer, 0, read)
        }
        if (count != artifact.bytes) corrupt(path, "byte count mismatch")
        if (digest.digest().toHex() != artifact.sha256) corrupt(path, "SHA-256 mismatch")
    }

    private fun corrupt(path: String, reason: String): Nothing =
        throw VmArtifactException("Isolated VM runtime artifact is corrupt: $path ($reason).")

    private fun InputStream.readBounded(maxBytes: Int, path: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > maxBytes) {
                throw VmArtifactException("Isolated VM runtime metadata is corrupt: $path.")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected)
    }

    private fun JsonObject.string(name: String): String {
        val value = getValue(name)
        require(value is JsonPrimitive && value.isString)
        return value.jsonPrimitive.content
    }

    private fun JsonObject.int(name: String): Int =
        requireNotNull(getValue(name).jsonPrimitive.intOrNull)

    private fun JsonObject.long(name: String): Long =
        requireNotNull(getValue(name).jsonPrimitive.longOrNull)

    private fun requireSha256(value: String) {
        require(SHA256.matches(value))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val ASSET_DIRECTORY = "vm"
        private const val MANIFEST_PATH = "$ASSET_DIRECTORY/build-manifest.json"
        private const val MANIFEST_SCHEMA = "phonecode-guest-build-manifest-v1"
        private const val ARCHITECTURE = "aarch64"
        private const val KERNEL = "vmlinuz"
        private const val INITRAMFS = "initramfs.cpio.gz"
        private const val SYSTEM_IMAGE = "system.img"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val REQUIRED_ARTIFACTS = listOf(KERNEL, INITRAMFS, SYSTEM_IMAGE)
        private val ARTIFACT_FIELDS = setOf("bytes", "name", "sha256")
        private val SYSTEM_PAYLOAD_FIELDS = setOf("bytes", "sha256")
        private val MANIFEST_FIELDS = setOf(
            "architecture",
            "artifacts",
            "build_epoch",
            "packages_lock_sha256",
            "protocol_version",
            "schema",
            "sources_lock_sha256",
            "system_payload",
            "toolchain_lock_sha256",
        )

        fun from(context: Context): VmArtifactStore {
            val app = context.applicationContext
            return VmArtifactStore(
                source = VmArtifactSource { path -> app.assets.open(path) },
                cacheDirectory = File(app.noBackupFilesDir, "verified-vm-artifacts"),
            )
        }
    }
}
