package dev.phonecode.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TransferBundleTest {

    private lateinit var source: File
    private lateinit var target: File

    @Before fun setUp() {
        source = Files.createTempDirectory("transfer-source").toFile()
        target = Files.createTempDirectory("transfer-target").toFile()
    }

    @After fun tearDown() {
        source.deleteRecursively()
        target.deleteRecursively()
    }

    private fun exportBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        TransferBundle.export(source, out)
        return out.toByteArray()
    }

    @Test fun appSettingsAreIncludedInTheBundle() {
        File(source, "app_settings.json").writeText("""{"themeMode":"DARK","customInstructions":"be terse"}""")
        val restored = TransferBundle.import(target, ByteArrayInputStream(exportBytes()))
        assertEquals(1, restored)
        assertEquals(File(source, "app_settings.json").readText(), File(target, "app_settings.json").readText())
    }

    @Test fun importRejectsNewerBundleVersions() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"app":"phonecode","version":99,"exportedAt":1}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("projects.json"))
            zos.write("[]".toByteArray())
            zos.closeEntry()
        }
        val failure = runCatching { TransferBundle.import(target, ByteArrayInputStream(out.toByteArray())) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("newer version"))
        assertFalse(File(target, "projects.json").exists())
    }

    @Test fun roundTripRestoresAllFilesAndContents() {
        File(source, "sessions").mkdirs()
        File(source, "config").mkdirs()
        File(source, "sessions/session-1.json").writeText("""{"id":"session-1","title":"A","updatedAt":1,"messages":[]}""")
        File(source, "sessions/session-2.json").writeText("""{"id":"session-2","title":"B","updatedAt":2,"messages":[]}""")
        File(source, "projects.json").writeText("""[{"id":"p1","name":"Proj"}]""")
        File(source, "model_prefs.json").writeText("""{"favourites":["anthropic/claude"],"recents":[]}""")
        File(source, "config/providers.json").writeText("""[{"id":"custom","baseUrl":"https://example.com"}]""")

        val restored = TransferBundle.import(target, ByteArrayInputStream(exportBytes()))

        assertEquals(5, restored)
        listOf(
            "sessions/session-1.json",
            "sessions/session-2.json",
            "projects.json",
            "model_prefs.json",
            "config/providers.json",
        ).forEach { rel ->
            assertEquals("content mismatch for $rel", File(source, rel).readText(), File(target, rel).readText())
        }
        // The manifest describes the bundle; it is not restored as a data file.
        assertFalse(File(target, "manifest.json").exists())
    }

    @Test fun exportIncludesManifest() {
        var manifest: String? = null
        ZipInputStream(ByteArrayInputStream(exportBytes())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") manifest = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        assertTrue(manifest!!.contains("\"app\":\"phonecode\""))
        assertTrue(manifest!!.contains("\"version\":1"))
        assertTrue(manifest!!.contains("\"exportedAt\":"))
    }

    @Test fun exportSkipsMissingOptionalFiles() {
        File(source, "sessions").mkdirs()
        File(source, "sessions/only.json").writeText("{}")

        val restored = TransferBundle.import(target, ByteArrayInputStream(exportBytes()))

        assertEquals(1, restored)
        assertFalse(File(target, "projects.json").exists())
    }

    @Test fun newerVersionManifestPlacedLastStillRestoresNothing() {
        // Hostile ordering: data entries BEFORE the manifest. Staging must prevent any write.
        File(target, "projects.json").writeText("original")
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("projects.json")); zos.write("evil".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"app":"phonecode","version":99,"exportedAt":0}""".toByteArray()); zos.closeEntry()
        }

        try {
            TransferBundle.import(target, ByteArrayInputStream(zip.toByteArray()))
            org.junit.Assert.fail("expected IOException for newer bundle version")
        } catch (expected: java.io.IOException) { }

        assertEquals("original", File(target, "projects.json").readText())
        assertFalse(File(target, ".import-staging").exists())
    }

    @Test fun bundleWithoutManifestIsRejectedWithoutWriting() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("projects.json")); zos.write("[]".toByteArray()); zos.closeEntry()
        }

        try {
            TransferBundle.import(target, ByteArrayInputStream(zip.toByteArray()))
            org.junit.Assert.fail("expected IOException for missing manifest")
        } catch (expected: java.io.IOException) { }

        assertFalse(File(target, "projects.json").exists())
    }

    @Test fun importOverwritesExistingFiles() {
        File(source, "projects.json").writeText("""[{"id":"new","name":"New"}]""")
        File(target, "projects.json").writeText("""[{"id":"old","name":"Old"}]""")

        TransferBundle.import(target, ByteArrayInputStream(exportBytes()))

        assertEquals("""[{"id":"new","name":"New"}]""", File(target, "projects.json").readText())
    }

    @Test fun maliciousAndUnknownEntriesAreSkipped() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            listOf(
                "manifest.json" to """{"app":"phonecode","version":1,"exportedAt":0}""",
                "../evil.txt" to "evil",
                "sessions/../../evil.json" to "evil",
                "/abs.json" to "evil",
                "sessions\\evil.json" to "evil",
                "unknown.txt" to "ignored",
                "projects.json" to "[]",
            ).forEach { (name, body) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }

        val restored = TransferBundle.import(target, ByteArrayInputStream(zip.toByteArray()))

        assertEquals(1, restored) // only projects.json
        assertEquals("[]", File(target, "projects.json").readText())
        assertFalse(File(target.parentFile, "evil.txt").exists())
        assertFalse(File(target, "evil.json").exists())
        assertFalse(File(target, "unknown.txt").exists())
    }
}
