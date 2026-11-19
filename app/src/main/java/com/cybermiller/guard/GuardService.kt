package com.cybermiller.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GuardService : Service() {

    private var scheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        TelegramPoller.start(this)

        scheduler = Executors.newSingleThreadScheduledExecutor().apply {
            // first health report after 30 s, then every 6 h
            schedule({ safe { HealthMonitor.report(this@GuardService) } }, 30, TimeUnit.SECONDS)
            scheduleAtFixedRate({ safe { HealthMonitor.report(this@GuardService) } },
                6, 6, TimeUnit.HOURS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TelegramPoller.stop()
        scheduler?.shutdownNow()
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun createChannel() {
        val ch = NotificationChannel(CH, "Guard Monitor", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CH)
            .setContentTitle("Guard active")
            .setContentText("Monitoring SMS, calls, device health")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    companion object {
        private const val CH = "guard"
        private const val NOTIF_ID = 1
    }
}