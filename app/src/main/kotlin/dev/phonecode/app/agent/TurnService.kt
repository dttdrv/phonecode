package dev.phonecode.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.phonecode.app.R

/**
 * Foreground service held ONLY while a turn is running: without it the system suspends the
 * process soon after the screen turns off and the streaming HTTP call dies mid-response
 * (device feedback). dataSync type, quiet low-importance notification, stopped the moment the
 * turn ends. No work happens here - the turn lives in the ViewModel; this just holds the lease.
 */
class TurnService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent activity",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the agent is working so responses survive the screen turning off." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PhoneCode is working")
            .setContentText("Agent turns and development processes stay active.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The foreground service keeps the process alive but does NOT keep the CPU awake; without this
        // partial wake lock the streaming HTTP call stalls once the screen turns off (Doze). Idempotent so
        // repeated start deliveries don't stack; the 60-min timeout is a safety net against a missed stop.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCode:turn")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "turn"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // Best-effort: background-start restrictions can reject this (rare; the app is in the
            // foreground when a turn starts) - the turn itself must never die over the notification.
            runCatching { context.startForegroundService(Intent(context, TurnService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnService::class.java)) }
        }
    }
}
