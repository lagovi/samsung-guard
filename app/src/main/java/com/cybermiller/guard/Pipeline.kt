package com.cybermiller.guard

import android.content.Context

/**
 * Pipeline orchestrates: SMS -> LLM verdict -> Telegram message with inline buttons.
 */
object Pipeline {
    fun onNewSms(ctx: Context, id: Long, address: String, body: String, ts: Long) {
        val appCtx = ctx.applicationContext
        // Called from a background thread already (SmsReceiver / DemoSms).
        val verdict = LlmClient.triage(appCtx, address, body)
        TelegramClient.sendSmsAlert(appCtx, id, address, body, verdict)
        SmsStore.markHandled(appCtx, id, verdict.verdict, verdict.explanation)
    }
}