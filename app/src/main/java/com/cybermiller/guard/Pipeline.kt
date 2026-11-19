package com.cybermiller.guard

import android.content.Context
import android.util.Log

/**
 * Serializes the SMS pipeline work so the UI/Receiver thread is never blocked.
 * Retries transient failures (slow LLM, network blips) with backoff.
 *
 * Entry points:
 *  - onNewSms()   from SmsReceiver / DemoSms — starts background thread
 *  - processSms() — do LLM + Telegram + store update
 */
object Pipeline {
    private const val TAG = "GuardPipeline"
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        java.lang.Thread(r, "guard-pipeline").apply { isDaemon = true }
    }

    /** Persist the SMS (already done by caller) and schedule triage in background. */
    fun onNewSms(ctx: Context, id: Long, address: String, body: String, ts: Long) {
        val appCtx = ctx.applicationContext
        exec.submit { processSms(appCtx, id, address, body) }
    }

    /**
     * Runs the actual triage. Retries up to 3 times with backoff for transient failures.
     */
    fun processSms(ctx: Context, id: Long, address: String, body: String) {
        var attempt = 0
        while (attempt < 3) {
            val verdict = LlmClient.triage(ctx, address, body)
            val failed = verdict.explanation.startsWith("LLM error") ||
                verdict.explanation.startsWith("HTTP ")
            if (!failed) {
                TelegramClient.sendSmsAlert(ctx, id, address, body, verdict)
                SmsStore.markHandled(ctx, id, verdict.verdict, verdict.explanation)
                Log.i(TAG, "handled #$id verdict=${verdict.verdict}")
                return
            }
            attempt++
            Log.w(TAG, "attempt $attempt failed for #$id: ${verdict.explanation}")
            if (attempt >= 3) {
                val fallback = Verdict("alert", "LLM недоступен: ${verdict.explanation.take(100)}", "med")
                TelegramClient.sendSmsAlert(ctx, id, address, body, fallback)
                SmsStore.markHandled(ctx, id, "alert", fallback.explanation)
                return
            }
            try { Thread.sleep(5_000L * attempt) } catch (_: InterruptedException) { return }
        }
    }
}