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
import dev.phonecode.app.R

/**
 * Foreground service held ONLY while a turn is running: without it the system suspends the
 * process soon after the screen turns off and the streaming HTTP call dies mid-response
 * (device feedback). dataSync type, quiet low-importance notification, stopped the moment the
 * turn ends. No work happens here - the turn lives in the ViewModel; this just holds the lease.
 */
class TurnService : Service() {

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
            .setContentText("Streaming continues in the background.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
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
