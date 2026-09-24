package dev.phonecode.app.agent

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.phonecode.app.PhoneCodeApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

class TurnService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean()

    // Runs on the main thread, like showTurnStatus. A dismissed Live Update stays gone until the next turn.
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            liveDismissed = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        register(this)
        ensureChannel(this)
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        register(this)
        promoteToForeground()
        if (shouldStopAfterStart(intent?.action == ACTION_STOP)) {
            if (intent?.action == ACTION_STOP) {
                stopWork(startId)
                return START_NOT_STICKY
            }
            if (stopForNoOwners(startId)) return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCode:turn")
                .apply {
                    setReferenceCounted(false)
                    // Bounded: a stuck turn must not keep the CPU awake indefinitely.
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification = buildTurnNotification(this, liveStatus, turnStartedAt)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(dismissReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        unregister(this)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWork(startId)
    }

    private fun stopWork(startId: Int) {
        synchronized(lifecycleLock) {
            desiredRunning = false
            if (activeService === this) activeService = null
        }
        val app = application as PhoneCodeApplication
        if (stopping.compareAndSet(false, true)) {
            app.turnScope.launch {
                try {
                    app.foregroundLeases.stopAll()
                } finally {
                    stopping.set(false)
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun stopForNoOwners(startId: Int? = null): Boolean {
        val shouldStop = synchronized(lifecycleLock) {
            if (desiredRunning) {
                false
            } else {
                if (activeService === this) activeService = null
                true
            }
        }
        if (!shouldStop) return false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelfResult(startId)
        return true
    }

    companion object {
        internal const val ACTION_STOP = "dev.phonecode.app.action.STOP_WORK"
        internal const val ACTION_DISMISSED = "dev.phonecode.app.action.TURN_NOTIFICATION_DISMISSED"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        private var liveStatus: TurnStatus? = null
        private var turnStartedAt = 0L
        private var liveDismissed = false
        private val lifecycleLock = Any()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private var desiredRunning = false
        private var startPending = false
        private var activeService: TurnService? = null

        /**
         * Main thread only (same thread as [stopForNoOwners]), so the foreground notification is
         * never re-posted after the service removed it, nor after the user dismissed it this turn. Ongoing states update the foreground
         * notification in place; a finished/failed turn posts one dismissible result, only when
         * PhoneCode is not on screen.
         */
        internal fun showTurnStatus(context: Context, status: TurnStatus?) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val ongoing = status?.takeIf { it.ongoing }
            if (ongoing != null && liveStatus == null) {
                turnStartedAt = System.currentTimeMillis()
                liveDismissed = false
                manager.cancel(RESULT_NOTIFICATION_ID)
            }
            liveStatus = ongoing
            if (!liveDismissed && synchronized(lifecycleLock) { activeService } != null) {
                manager.notify(NOTIFICATION_ID, buildTurnNotification(context, ongoing, turnStartedAt))
            }
            if (status != null && !status.ongoing && !appVisible()) {
                ensureChannel(context)
                manager.notify(RESULT_NOTIFICATION_ID, buildTurnNotification(context, status, turnStartedAt))
            }
        }

        private fun appVisible(): Boolean = ActivityManager.RunningAppProcessInfo()
            .also(ActivityManager::getMyMemoryState)
            .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                TURN_CHANNEL_ID,
                "Agent activity",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while PhoneCode is working in the background."
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val shouldStart = synchronized(lifecycleLock) {
                desiredRunning = true
                if (activeService != null || startPending) {
                    false
                } else {
                    startPending = true
                    true
                }
            }
            if (!shouldStart) return
            try {
                context.startForegroundService(Intent(context, TurnService::class.java))
            } catch (error: Throwable) {
                synchronized(lifecycleLock) { startPending = false }
                throw error
            }
        }

        fun stop(context: Context) {
            val service = synchronized(lifecycleLock) {
                desiredRunning = false
                activeService
            }
            if (service != null) mainHandler.post { service.stopForNoOwners() }
        }

        private fun register(service: TurnService) {
            synchronized(lifecycleLock) { activeService = service }
        }

        private fun shouldStopAfterStart(explicitStop: Boolean): Boolean = synchronized(lifecycleLock) {
            startPending = false
            if (explicitStop) desiredRunning = false
            !desiredRunning
        }

        private fun unregister(service: TurnService) {
            synchronized(lifecycleLock) {
                if (activeService === service) {
                    activeService = null
                    startPending = false
                }
            }
        }
    }
}

private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
