package com.cybermiller.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // goAsync gives us up to ~30s before the process may be killed
        val pending = goAsync()
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .map { Triple(it.displayOriginatingAddress ?: "unknown",
                          it.displayMessageBody ?: "",
                          it.timestampMillis) }
        Thread {
            try {
                for ((addr, body, ts) in msgs) {
                    SmsStore.onSmsReceived(context.applicationContext, addr, body, ts)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}