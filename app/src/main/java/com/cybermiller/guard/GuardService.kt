package com.cybermiller.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        TelegramPoller.start(this)
        CallMonitor.start(this)
        ViberMonitor.start(this)
        HealthMonitor.start(this)

        // Telegram menu (Меню → /health, /start). Idempotent.
        TelegramClient.setMyCommands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TelegramPoller.stop()
        CallMonitor.stop(this)
        ViberMonitor.stop()
        HealthMonitor.stop()
        super.onDestroy()
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