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
            "wire IsolatedVmController into the production turn runtime",
            "reconcile source-level copyright and NOTICE obligations for runtime components",
            "audit the complete signed AAB native graph and upload native debug symbols",
            "complete signed-device VM lifecycle and Play artifact evidence",
            "verifyPlaySubmission",
        ).forEach { requiredGate ->
            assertTrue("Play release gate is missing: $requiredGate", build.contains(requiredGate))
        }
    }
}
