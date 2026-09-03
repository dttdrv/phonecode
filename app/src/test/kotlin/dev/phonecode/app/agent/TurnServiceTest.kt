package dev.phonecode.app.agent

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.PhoneCodeApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TurnServiceTest {
    @Test
    fun establishedServiceStopsThroughItsActiveInstance() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val service = controller.get()
        TurnService.start(app)
        service.onStartCommand(Intent(), 0, 1)

        TurnService.stop(app)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    @Test
    fun canceledPendingStartPromotesThenStopsWithoutAcquiringWork() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val service = controller.get()

        TurnService.stop(app)
        service.onStartCommand(Intent(), 0, 1)

        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun leaseReacquiredBeforePostedStopKeepsEstablishedService() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val service = controller.get()
        TurnService.start(app)
        service.onStartCommand(Intent(), 0, 1)

        TurnService.stop(app)
        TurnService.start(app)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!shadowOf(service).isStoppedBySelf)
        TurnService.stop(app)
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
    }

    @Test
    fun foregroundTimeoutStartsAReplacementForLeaseAcquiredByStopHandler() {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val service = controller.get()
        val replacementAcquired = CountDownLatch(1)
        app.foregroundLeases.acquire("old-turn")
        service.onStartCommand(Intent(), 0, 1)
        app.foregroundLeases.registerStopHandler("replacement-test") {
            app.foregroundLeases.acquire("replacement-turn")
            replacementAcquired.countDown()
        }
        while (shadowOf(app).nextStartedService != null) Unit

        try {
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

            assertTrue(replacementAcquired.await(5, TimeUnit.SECONDS))
            val replacementIntent = requireNotNull(shadowOf(app).nextStartedService)
            assertEquals(TurnService::class.java.name, replacementIntent.component?.className)
            val replacementController = Robolectric.buildService(TurnService::class.java).create()
            replacementController.get().onStartCommand(replacementIntent, 0, 2)
            app.foregroundLeases.release("replacement-turn")
            shadowOf(Looper.getMainLooper()).idle()
            replacementController.destroy()
        } finally {
            app.foregroundLeases.unregisterStopHandler("replacement-test")
            app.foregroundLeases.release("old-turn")
            app.foregroundLeases.release("replacement-turn")
            controller.destroy()
        }
    }

    @Test
    fun everyStartCommandPromotesBeforeHandlingAnImmediateStop() {
        val root = generateSequence(
            java.io.File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
        ) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(root, "app/src/main/kotlin/dev/phonecode/app/agent/TurnService.kt").readText()
        val command = source.substringAfter("override fun onStartCommand").substringBefore("override fun onDestroy")
        val stop = source.substringAfter("fun stop(context: Context)").substringBefore("private fun register")

        assertTrue(command.indexOf("promoteToForeground()") < command.indexOf("intent?.action == ACTION_STOP"))
        assertTrue(source.substringAfter("private fun promoteToForeground()").contains("startForeground("))
        assertTrue(stop.contains("desiredRunning = false"))
        assertTrue(stop.contains("service.stopForNoOwners()"))
        assertTrue(!stop.contains("startForegroundService("))
        assertTrue(!stop.contains("context.stopService("))
        assertTrue(command.contains("register(this)"))
        val noOwners = source.substringAfter("private fun stopForNoOwners").substringBefore("companion object")
        assertTrue(noOwners.indexOf("activeService = null") < noOwners.indexOf("stopSelf"))
    }

    @Test
    fun notificationStopDoesNotRunStopHandlersOnTheCallbackThread() {
        assertStopIsDispatched {
            it.onStartCommand(
                Intent().setAction("dev.phonecode.app.action.STOP_WORK"),
                0,
                1,
            )
        }
    }

    @Test
    fun foregroundTimeoutDoesNotRunStopHandlersOnTheCallbackThread() {
        assertStopIsDispatched {
            it.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun assertStopIsDispatched(stop: (TurnService) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()
        val controller = Robolectric.buildService(TurnService::class.java).create()
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val handlerThread = AtomicReference<Thread>()
        val failure = AtomicReference<Throwable?>()
        app.foregroundLeases.registerStopHandler("turn-service-test") {
            handlerThread.set(Thread.currentThread())
            handlerEntered.countDown()
            releaseHandler.await(5, TimeUnit.SECONDS)
        }
        val caller = thread {
            callbackThread.set(Thread.currentThread())
            runCatching { stop(controller.get()) }.onFailure(failure::set)
            callbackReturned.countDown()
        }

        try {
            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS))
            assertTrue(callbackReturned.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertNotSame(callbackThread.get(), handlerThread.get())
        } finally {
            releaseHandler.countDown()
            caller.join(5_000)
            app.foregroundLeases.unregisterStopHandler("turn-service-test")
            controller.destroy()
        }
    }
}
