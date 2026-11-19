package com.cybermiller.guard

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serializes the SMS pipeline work so the UI/Receiver thread is never blocked.
 * Retries transient failures (slow LLM, network blips) with backoff.
 */
object Pipeline {
    private const val TAG = "GuardPipeline"
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "guard-pipeline").apply { isDaemon = true }
    }

    fun onNewSms(ctx: Context, id: Long, address: String, body: String, ts: Long) {
        val appCtx = ctx.applicationContext
        exec.submit { runWithRetry(appCtx, id, address, body) }
    }

    private fun runWithRetry(ctx: Context, id: Long, address: String, body: String) {
        var attempt = 0
        while (attempt < 3) {
            val verdict = LlmClient.triage(ctx, address, body)
            // treat "LLM error"/HTTP failures as retryable
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
                // notify owner that SMS could not be triaged, still forward raw text
                val fallback = Verdict("alert", "LLM недоступен: ${verdict.explanation.take(100)}", "med")
                TelegramClient.sendSmsAlert(ctx, id, address, body, fallback)
                SmsStore.markHandled(ctx, id, "alert", fallback.explanation)
                return
            }
            try { TimeUnit.SECONDS.sleep(5L * attempt) } catch (_: InterruptedException) { return }
        }
    }

    /** Manual triage trigger used by the demo button (runs on queue as well). */
    fun enqueueDemo(ctx: Context) = exec.submit { }
}