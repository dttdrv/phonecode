package dev.phonecode.app.runtime

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRuntimePackagingTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun distributableApksContainTheLockedMisulRuntimeAndProvenance() {
        val lock = File(root, "native-misul/sources.lock").readLines()
            .filter(String::isNotBlank)
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        val library = File(root, "app/src/main/jniLibs/arm64-v8a/libmisul.so")

        assertTrue("The distributable Misul library is missing", library.isFile)
        assertEquals(lock.getValue("artifact_bytes").toLong(), library.length())
        assertEquals(lock.getValue("artifact_sha256"), sha256(library))
        listOf("MANIFEST.sha256", "SOURCE-MANIFEST.sha256", "SOURCES.lock").forEach { name ->
            assertTrue(
                "The distributable Misul provenance file is missing: $name",
                File(root, "app/src/main/assets/misul-runtime/$name").isFile,
            )
        }
        assertTrue(
            "CI must verify the runtime inside the distributable APK",
            File(root, ".github/workflows/checks.yml").readText()
                .contains(":app:verifySideloadMisulApk"),
        )
    }

    @Test
    fun prototypeRuntimeIsDebugOnly() {
        val prototypeFiles = listOf(
            "assets/alpine-aarch64.rootfs",
            "jniLibs/arm64-v8a/libproot.so",
            "jniLibs/arm64-v8a/libproot-loader.so",
        )

        prototypeFiles.forEach { relativePath ->
            assertTrue(
                "$relativePath must remain available to development builds",
                File(root, "app/src/debug/$relativePath").isFile,
            )
            assertFalse(
                "$relativePath must not be inherited by Google Play release builds",
                File(root, "app/src/main/$relativePath").exists(),
            )
            assertFalse(
                "$relativePath must not be added directly to the release source set",
                File(root, "app/src/release/$relativePath").exists(),
            )
        }
    }

    @Test
    fun playGateCannotOpenBeforeGuestAndProductionIntegrationAreComplete() {
        val build = File(root, "app/build.gradle.kts").readText()
        val guestRuntimeInputs = requireNotNull(
            Regex(
                """val releaseGuestRuntimeFiles = listOf\((.*?)\n\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(build),
        ) { "releaseGuestRuntimeFiles must remain an explicit fail-closed input list" }.groupValues[1]

        listOf(
            "src/release/assets/vm/vmlinuz",
            "src/release/assets/vm/initramfs.cpio.gz",
            "src/release/assets/vm/system.img",
            "src/release/assets/vm/build-manifest.json",
        ).forEach { requiredInput ->
            assertTrue(
                "Guest runtime gate is missing an input: $requiredInput",
                guestRuntimeInputs.contains(requiredInput),
            )
        }

        listOf(
            "native-runtime/out/symbols/arm64-v8a",
            "generated/phonecodeReleaseHostRuntime",
            "prepareReleaseHostEvidence",
            "stageReleaseHostRuntime",
            "verifyReleaseHostEvidence",
            "src/release/assets/licenses/guest",
            "implement authenticated host-project workspace transport for the isolated VM runtime",
            "reconcile source-level copyright and NOTICE obligations for runtime components",
            "audit the complete signed AAB native graph and upload native debug symbols",
            "complete signed-device VM lifecycle and Play artifact evidence",
            "verifyPlaySubmission",
        ).forEach { requiredGate ->
            assertTrue("Play release gate is missing: $requiredGate", build.contains(requiredGate))
        }
    }

    @Test
    fun releaseCandidateUsesVersion050EvidencePaths() {
        val build = File(root, "app/build.gradle.kts").readText()

        assertTrue(build.contains("""versionCode = 56"""))
        assertTrue(build.contains("""versionName = "0.6.0-beta.2""""))
        assertTrue(build.contains("""release-evidence/0.5.1/vm-host"""))
        assertTrue(build.contains("""release-evidence/0.5.1/guest/sources"""))
        assertTrue(build.contains("""play/0.5.1/submission-evidence.json"""))
        assertTrue(File(root, "play/0.5.1/README.md").isFile)

        listOf(
            "native-runtime/prepare-release-host-evidence.sh",
            "native-runtime/stage-release-host-runtime.sh",
            "legal/generate-android-jvm-evidence.py",
            "legal/generate-mermaid-evidence.py",
            "legal/release/android-jvm-SBOM.cdx.json",
            "legal/release/mermaid-SBOM.cdx.json",
            "guest-runtime/build-guest.sh",
            "guest-runtime/packages.lock",
            "guest-runtime/tests/guest-build-test.sh",
        ).forEach { relativePath ->
            val text = File(root, relativePath).readText()
            assertFalse("$relativePath still contains the previous release identity", text.contains("0.5.0"))
            assertTrue("$relativePath does not contain the 0.5.1 release identity", text.contains("0.5.1"))
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
