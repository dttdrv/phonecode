package dev.phonecode.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class EnvironmentBootstrapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun bindsOnlyTheCanonicalWorkspaceAndRequiredGuestPaths() {
        val workspacesRoot = tmp.newFolder("workspaces")
        val workspace = File(workspacesRoot, "default").apply { mkdirs() }
        val rootfs = tmp.newFolder("rootfs")
        val guestTmp = tmp.newFolder("guest-tmp")
        val argv = linuxShellArgv(
            File(tmp.root, "libproot.so"),
            rootfs,
            guestTmp,
            workspacesRoot,
            File(workspace, ".").path,
        )

        val binds = argv.windowed(2).filter { it.first() == "-b" }.map { it.last() }
        assertEquals(
            listOf("/dev", "/proc", "${guestTmp.canonicalPath}:/tmp", "${workspace.canonicalPath}:/workspace"),
            binds,
        )
        assertEquals("/workspace", argv[argv.indexOf("-w") + 1])
    }

    @Test fun rejectsAWorkspaceOutsideTheWorkspaceRoot() {
        val workspacesRoot = tmp.newFolder("workspaces")
        val outside = tmp.newFolder("outside")

        assertThrows(IllegalArgumentException::class.java) {
            linuxShellArgv(
                File(tmp.root, "libproot.so"),
                tmp.newFolder("rootfs"),
                tmp.newFolder("guest-tmp"),
                workspacesRoot,
                outside.absolutePath,
            )
        }
    }

    @Test fun usesTheDeviceDnsServersWithoutPublicFallbacks() {
        assertEquals("nameserver 10.0.0.1\nnameserver 2001:db8::1\n", resolvConf(listOf("10.0.0.1", "2001:db8::1", "10.0.0.1")))
        assertEquals("", resolvConf(emptyList()))
    }

    @Test fun acceptsTheBundledAbsoluteShellSymlinkAfterSmokeTest() {
        val rootfs = tmp.newFolder("linux")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        File(bin, "busybox").writeText("busybox")
        val shell = File(bin, "sh")
        Files.createSymbolicLink(shell.toPath(), Path.of("/bin/busybox"))
        val marker = File(tmp.root, "linux.ready").apply { writeText("ok") }

        assertEquals(Path.of("/bin/busybox"), Files.readSymbolicLink(shell.toPath()))
        assertTrue(linuxTreeReady(marker, rootfs))
    }

    @Test fun migratesTheLegacyMarkerWithoutReplacingTheUserland() {
        val rootfs = tmp.newFolder("existing-linux")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        File(bin, "busybox").writeText("user-installed-state")
        Files.createSymbolicLink(File(bin, "sh").toPath(), Path.of("/bin/busybox"))
        val legacy = File(tmp.root, "existing-linux-3.21.7.ready").apply { writeText("ok") }
        val current = File(tmp.root, "existing-linux-3.21.7-hash.ready")

        assertTrue(migrateLinuxMarker(current, legacy, rootfs))
        assertTrue(current.isFile)
        assertFalse(legacy.exists())
        assertEquals("user-installed-state", File(bin, "busybox").readText())
    }
}
