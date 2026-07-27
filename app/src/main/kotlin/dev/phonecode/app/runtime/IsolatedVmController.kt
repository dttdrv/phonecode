package dev.phonecode.app.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

internal class IsolatedVmController(context: Context) {
    private val context = context.applicationContext

    suspend fun start(
        kernel: ParcelFileDescriptor,
        initramfs: ParcelFileDescriptor,
        systemImage: ParcelFileDescriptor,
    ): IsolatedVmSession {
        val owned = mutableListOf<ParcelFileDescriptor>()
        return try {
            fun own(descriptor: ParcelFileDescriptor) = descriptor.also(owned::add)

            val kernelCopy = own(ParcelFileDescriptor.dup(kernel.fileDescriptor))
            val initramfsCopy = own(ParcelFileDescriptor.dup(initramfs.fileDescriptor))
            val systemImageCopy = own(ParcelFileDescriptor.dup(systemImage.fileDescriptor))
            val console = ParcelFileDescriptor.createSocketPair().onEach(owned::add)
            val control = ParcelFileDescriptor.createSocketPair().onEach(owned::add)
            VmConnection(
                context = context,
                serviceDescriptors = arrayOf(
                    kernelCopy,
                    initramfsCopy,
                    systemImageCopy,
                    console[1],
                    control[1],
                ),
                console = console[0],
                control = control[0],
            ).bind().also { owned.clear() }
        } finally {
            owned.forEach { runCatching { it.close() } }
        }
    }
}

internal class IsolatedVmSession internal constructor(
    val console: ParcelFileDescriptor,
    val control: ParcelFileDescriptor,
    private val closeConnection: () -> Unit,
) : Closeable {
    override fun close() = closeConnection()
}

private class VmConnection(
    private val context: Context,
    private val serviceDescriptors: Array<ParcelFileDescriptor>,
    private val console: ParcelFileDescriptor,
    private val control: ParcelFileDescriptor,
) : ServiceConnection {
    private val lock = Any()
    private var bound = false
    private var closed = false
    private var continuation: CancellableContinuation<IsolatedVmSession>? = null
    private var remote: IIsolatedVmService? = null

    suspend fun bind(): IsolatedVmSession = suspendCancellableCoroutine { continuation ->
        synchronized(lock) { this.continuation = continuation }
        continuation.invokeOnCancellation { close(stopRemote = true) }

        val binding = runCatching {
            context.bindService(
                Intent(context, IsolatedQemuService::class.java),
                this,
                Service.BIND_AUTO_CREATE,
            )
        }
        binding.exceptionOrNull()?.let {
            fail(it)
            return@suspendCancellableCoroutine
        }
        if (!binding.getOrThrow()) {
            fail(IllegalStateException("Isolated VM service unavailable"))
            return@suspendCancellableCoroutine
        }

        val unbind = synchronized(lock) {
            bound = true
            if (closed) {
                bound = false
                true
            } else {
                false
            }
        }
        if (unbind) runCatching { context.unbindService(this) }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val result = synchronized(lock) {
            if (closed) return
            runCatching {
                val service = IIsolatedVmService.Stub.asInterface(binder)
                remote = service
                service.start(
                    serviceDescriptors[0],
                    serviceDescriptors[1],
                    serviceDescriptors[2],
                    serviceDescriptors[3],
                    serviceDescriptors[4],
                )
                closeServiceDescriptors()
                IsolatedVmSession(console, control) { close(stopRemote = true) }
            }
        }
        result.onSuccess(::complete).onFailure(::fail)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        fail(IllegalStateException("Isolated VM service disconnected"))
    }

    override fun onBindingDied(name: ComponentName) {
        fail(IllegalStateException("Isolated VM service binding died"))
    }

    override fun onNullBinding(name: ComponentName) {
        fail(IllegalStateException("Isolated VM service returned no binder"))
    }

    private fun complete(session: IsolatedVmSession) {
        val waiting = synchronized(lock) { continuation.also { continuation = null } }
        if (waiting == null) close(stopRemote = true)
        else waiting.resumeWith(Result.success(session))
    }

    private fun fail(error: Throwable) {
        val waiting = synchronized(lock) {
            if (closed) null else continuation.also { continuation = null }
        }
        close(stopRemote = true)
        waiting?.resumeWith(Result.failure(error))
    }

    private fun close(stopRemote: Boolean) {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            val snapshot = remote to bound
            remote = null
            bound = false
            snapshot
        }
        if (stopRemote) runCatching { state.first?.stop() }
        closeServiceDescriptors()
        runCatching { console.close() }
        runCatching { control.close() }
        if (state.second) runCatching { context.unbindService(this) }
    }

    private fun closeServiceDescriptors() {
        serviceDescriptors.forEach { runCatching { it.close() } }
    }
}
