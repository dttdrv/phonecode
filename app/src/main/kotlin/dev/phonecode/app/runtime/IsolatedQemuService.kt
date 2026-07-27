package dev.phonecode.app.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File

class IsolatedQemuService : Service() {
    private val lock = Any()
    private var pid = 0

    private val binder = object : IIsolatedVmService.Stub() {
        override fun start(
            kernel: ParcelFileDescriptor,
            initramfs: ParcelFileDescriptor,
            systemImage: ParcelFileDescriptor,
            console: ParcelFileDescriptor,
            control: ParcelFileDescriptor,
        ) {
            synchronized(lock) {
                if (pid != 0 && !QemuNative.isRunning(pid)) pid = 0
                check(pid == 0) { "VM already running" }

                val executable = File(applicationInfo.nativeLibraryDir, "libphonecode_qemu.so")
                check(executable.isFile) { "QEMU runtime unavailable" }

                val descriptors = arrayOf(kernel, initramfs, systemImage, console, control)
                val detached = intArrayOf(-1, -1, -1, -1, -1)
                try {
                    QemuNative.ensureLoaded()
                    descriptors.forEachIndexed { index, descriptor ->
                        detached[index] = descriptor.detachFd()
                    }
                    val owned = detached.copyOf()
                    detached.fill(-1)
                    val result = QemuNative.start(
                        executable.absolutePath,
                        QemuLaunchConfig.arguments,
                        owned[0],
                        owned[1],
                        owned[2],
                        owned[3],
                        owned[4],
                    )
                    check(result > 0) { "QEMU failed to start: ${-result}" }
                    pid = result
                } finally {
                    descriptors.forEach { runCatching { it.close() } }
                    detached.filter { it >= 0 }.forEach {
                        runCatching { ParcelFileDescriptor.adoptFd(it).close() }
                    }
                }
            }
        }

        override fun stop() {
            stopChild()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        stopChild()
        return false
    }

    override fun onDestroy() {
        stopChild()
        super.onDestroy()
    }

    private fun stopChild() {
        val child = synchronized(lock) {
            pid.also { pid = 0 }
        }
        if (child > 0) QemuNative.stop(child)
    }

}

internal object QemuLaunchConfig {
    const val kernelFd = 3
    const val initramfsFd = 4
    const val systemImageFd = 5
    const val controlFd = 6

    val arguments = arrayOf(
        "-M", "virt",
        "-cpu", "max",
        "-accel", "tcg,thread=multi",
        "-smp", "1",
        "-m", "256",
        "-nic", "none",
        "-display", "none",
        "-serial", "stdio",
        "-no-reboot",
        "-kernel", "/proc/self/fd/$kernelFd",
        "-initrd", "/proc/self/fd/$initramfsFd",
        "-drive", "file=/proc/self/fd/$systemImageFd,format=raw,if=none,id=system,readonly=on",
        "-device", "virtio-blk-device,drive=system",
        "-device", "virtio-serial-device",
        "-chardev", "socket,id=phonecode_control,fd=$controlFd",
        "-device", "virtserialport,chardev=phonecode_control,name=dev.phonecode.guestd",
        "-append", "earlycon=pl011,0x09000000 console=ttyAMA0 panic=-1",
    )
}
