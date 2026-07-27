package dev.phonecode.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IsolatedVmControllerTest {
    @Test
    fun closeStopsTheVmAndUnbindsExactlyOnceWithoutTakingCallerDescriptors() = runBlocking {
        val events = mutableListOf<String>()
        val service = object : IIsolatedVmService.Stub() {
            override fun start(
                kernel: ParcelFileDescriptor,
                initramfs: ParcelFileDescriptor,
                systemImage: ParcelFileDescriptor,
                console: ParcelFileDescriptor,
                control: ParcelFileDescriptor,
            ) {
                events += "start"
            }

            override fun stop() {
                events += "stop"
            }
        }
        val context = RecordingContext(service, events)
        val kernel = ParcelFileDescriptor.createPipe()
        val initramfs = ParcelFileDescriptor.createPipe()
        val systemImage = ParcelFileDescriptor.createPipe()

        try {
            val session = IsolatedVmController(context).start(kernel[0], initramfs[0], systemImage[0])

            assertTrue(kernel[0].fileDescriptor.valid())
            assertTrue(initramfs[0].fileDescriptor.valid())
            assertTrue(systemImage[0].fileDescriptor.valid())
            session.close()
            session.close()

            assertEquals(listOf("bind", "start", "stop", "unbind"), events)
        } finally {
            kernel.forEach { it.close() }
            initramfs.forEach { it.close() }
            systemImage.forEach { it.close() }
        }
    }

    @Test
    fun cancellingAStartThatHasNotConnectedUnbindsTheService() = runBlocking {
        val events = mutableListOf<String>()
        val service = object : IIsolatedVmService.Stub() {
            override fun start(
                kernel: ParcelFileDescriptor,
                initramfs: ParcelFileDescriptor,
                systemImage: ParcelFileDescriptor,
                console: ParcelFileDescriptor,
                control: ParcelFileDescriptor,
            ) = Unit

            override fun stop() {
                events += "stop"
            }
        }
        val context = RecordingContext(service, events, connect = false)
        val kernel = ParcelFileDescriptor.createPipe()
        val initramfs = ParcelFileDescriptor.createPipe()
        val systemImage = ParcelFileDescriptor.createPipe()

        try {
            val start = launch {
                IsolatedVmController(context).start(kernel[0], initramfs[0], systemImage[0])
            }
            yield()
            start.cancelAndJoin()

            assertEquals(listOf("bind", "unbind"), events)
            assertTrue(kernel[0].fileDescriptor.valid())
            assertTrue(initramfs[0].fileDescriptor.valid())
            assertTrue(systemImage[0].fileDescriptor.valid())
        } finally {
            kernel.forEach { it.close() }
            initramfs.forEach { it.close() }
            systemImage.forEach { it.close() }
        }
    }

    @Test
    fun failedRemoteStartUnbindsAndLeavesCallerDescriptorsOpen() = runBlocking {
        val events = mutableListOf<String>()
        val service = object : IIsolatedVmService.Stub() {
            override fun start(
                kernel: ParcelFileDescriptor,
                initramfs: ParcelFileDescriptor,
                systemImage: ParcelFileDescriptor,
                console: ParcelFileDescriptor,
                control: ParcelFileDescriptor,
            ) {
                events += "start"
                error("runtime unavailable")
            }

            override fun stop() {
                events += "stop"
            }
        }
        val context = RecordingContext(service, events)
        val kernel = ParcelFileDescriptor.createPipe()
        val initramfs = ParcelFileDescriptor.createPipe()
        val systemImage = ParcelFileDescriptor.createPipe()

        try {
            val error = runCatching {
                IsolatedVmController(context).start(kernel[0], initramfs[0], systemImage[0])
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("runtime unavailable", error?.message)
            assertEquals(listOf("bind", "start", "stop", "unbind"), events)
            assertTrue(kernel[0].fileDescriptor.valid())
            assertTrue(initramfs[0].fileDescriptor.valid())
            assertTrue(systemImage[0].fileDescriptor.valid())
        } finally {
            kernel.forEach { it.close() }
            initramfs.forEach { it.close() }
            systemImage.forEach { it.close() }
        }
    }

    private class RecordingContext(
        private val service: IIsolatedVmService,
        private val events: MutableList<String>,
        private val connect: Boolean = true,
    ) : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getApplicationContext(): Context = this

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            events += "bind"
            if (connect) {
                connection.onServiceConnected(
                    ComponentName(this, IsolatedQemuService::class.java),
                    service.asBinder(),
                )
            }
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            events += "unbind"
        }
    }
}
