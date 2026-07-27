package dev.phonecode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuLaunchConfigTest {
    @Test
    fun systemImageAndGuestProtocolUseDedicatedFileDescriptors() {
        val arguments = QemuLaunchConfig.arguments.toList()

        assertTrue(arguments.windowed(2).contains(listOf("-drive", "file=/proc/self/fd/5,format=raw,if=none,id=system,readonly=on")))
        assertTrue(arguments.windowed(2).contains(listOf("-device", "virtio-blk-device,drive=system")))
        assertTrue(arguments.windowed(2).contains(listOf("-chardev", "socket,id=phonecode_control,fd=6")))
        assertTrue(arguments.windowed(2).contains(listOf("-device", "virtserialport,chardev=phonecode_control,name=dev.phonecode.guestd")))
        assertFalse(arguments.contains("-mon"))
        assertEquals(3, QemuLaunchConfig.kernelFd)
        assertEquals(4, QemuLaunchConfig.initramfsFd)
        assertEquals(5, QemuLaunchConfig.systemImageFd)
        assertEquals(6, QemuLaunchConfig.controlFd)
    }
}
