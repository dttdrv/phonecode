package dev.phonecode.app.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRuntimePackagingTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

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

        listOf(
            "src/release/assets/vm/vmlinuz",
            "src/release/assets/vm/initramfs.cpio.gz",
            "src/release/assets/vm/system.img",
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

        assertTrue(build.contains("""versionCode = 50"""))
        assertTrue(build.contains("""versionName = "0.5.0""""))
        assertTrue(build.contains("""release-evidence/0.5.0/vm-host"""))
        assertTrue(build.contains("""release-evidence/0.5.0/guest/sources"""))
        assertTrue(build.contains("""play/0.5.0/submission-evidence.json"""))
        assertTrue(File(root, "play/0.5.0/README.md").isFile)
    }
}
