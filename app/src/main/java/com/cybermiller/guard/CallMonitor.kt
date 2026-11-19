package com.cybermiller.guard

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Tracks phone state transitions and reports meaningful events to Telegram:
 * incoming call started, call answered, call ended (with duration).
 * Uses PhoneStateListener — cheap, no polling.
 */
object CallMonitor {
    private const val TAG = "GuardCall"

    private var listener: PhoneStateListener? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var ringingSince = 0L
    private var offhookSince = 0L
    private var lastNumber: String? = null

    fun start(ctx: Context) {
        if (listener != null) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val appCtx = ctx.applicationContext
        @Suppress("DEPRECATION")
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                handle(appCtx, state, incomingNumber)
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        listener = l
        Log.i(TAG, "call monitor started")
    }

    fun stop(ctx: Context) {
        val l = listener ?: return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        @Suppress("DEPRECATION")
        tm.listen(l, PhoneStateListener.LISTEN_NONE)
        listener = null
        Log.i(TAG, "call monitor stopped")
    }

    private fun handle(ctx: Context, state: Int, number: String?) {
        if (state == lastState) return
        val now = System.currentTimeMillis()
        Log.i(TAG, "state $lastState -> $state num=$number")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                ringingSince = now
                lastNumber = number
                val who = if (number.isNullOrBlank()) "неизвестный номер" else number
                TelegramClient.sendText("📞 <b>Входящий звонок</b>\n<code>$who</code>")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                offhookSince = now
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // call finished (or missed)
                val wasRinging = ringingSince > 0
                val wasTalking = offhookSince > 0
                val who = lastNumber ?: "неизвестный номер"
                when {
                    wasTalking -> {
                        val sec = ((now - offhookSince) / 1000).coerceAtLeast(0)
                        val mm = sec / 60
                        val ss = sec % 60
                        TelegramClient.sendText(
                            "✅ <b>Звонок завершён</b>\n<code>$who</code>\nДлительность: ${mm}м ${ss}с"
                        )
                    }
                    wasRinging -> {
                        TelegramClient.sendText("❌ <b>Пропущенный звонок</b>\n<code>$who</code>")
                    }
                }
                ringingSince = 0
                offhookSince = 0
                lastNumber = null
            }
        }
        lastState = state
    }
}