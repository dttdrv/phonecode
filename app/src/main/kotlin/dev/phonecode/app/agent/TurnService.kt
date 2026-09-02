package dev.phonecode.app.agent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

class TurnService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent activity",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Misul Agent is working in the background."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWork(startId)
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TurnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Misul Agent is working")
            .setContentText("Agent work and local processes remain active.")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCode:turn")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWork(startId)
    }

    private fun stopWork(startId: Int) {
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

    companion object {
        private const val ACTION_STOP = "dev.phonecode.app.action.STOP_WORK"
        private const val CHANNEL_ID = "turn"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TurnService::class.java))
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnService::class.java)) }
        }
    }
}
