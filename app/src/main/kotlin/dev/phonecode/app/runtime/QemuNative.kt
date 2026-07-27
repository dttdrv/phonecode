package dev.phonecode.app.runtime

internal object QemuNative {
    init {
        System.loadLibrary("phonecode_vm")
    }

    fun ensureLoaded() = Unit

    external fun start(
        executable: String,
        arguments: Array<String>,
        kernelFd: Int,
        initramfsFd: Int,
        systemImageFd: Int,
        consoleFd: Int,
        controlFd: Int,
    ): Int

    external fun isRunning(pid: Int): Boolean

    external fun stop(pid: Int): Int
}
