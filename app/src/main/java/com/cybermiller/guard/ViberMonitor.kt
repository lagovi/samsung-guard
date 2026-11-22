package com.cybermiller.guard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Forwards incoming Viber messages to the Telegram owner chat.
 *
 * Data source: the custom Viber "api" build's LiteAPI. Two channels feed this
 * monitor, deduplicated by the global message id:
 *  - broadcast `com.viber.voip.liteapi.INCOMING` (instant, see ViberEventReceiver);
 *  - polling /events every 10 s (catch-up after restarts, ring buffer of 100).
 *
 * The watermark (max forwarded msg id) is persisted in SharedPreferences, so a
 * service restart resumes exactly where it stopped — and the very first launch
 * primes it to the current newest message instead of spamming the whole history.
 */
object ViberMonitor {
    private const val TAG = "GuardViber"
    private const val POLL_INTERVAL_MS = 10_000L
    private val sdf = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    @Volatile private var running = false

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    @Volatile private var watermark: Long = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        val p = prefs(appCtx)
        watermark = p.getLong("viber_fwd_max_id", -1L)
        Log.i(TAG, "viber monitor start, stored watermark=$watermark")
        Thread {
            try {
                if (watermark < 0) prime(appCtx)
                pollEvents(appCtx)
                while (running) {
                    try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) {}
                    try { pollEvents(appCtx) } catch (t: Throwable) {
                        Log.w(TAG, "poll failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                // Never let the monitor thread kill the whole process.
                Log.w(TAG, "monitor crashed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    /** First launch ever: do not forward the existing history as "new". */
    private fun prime(ctx: Context) {
        var max = 0L
        for (chat in ViberApi.chats(ctx)) {
            val newest = ViberApi.messages(ctx, chat.chatId, 1).firstOrNull()?.id ?: continue
            if (newest > max) max = newest
        }
        watermark = max
        save(ctx)
        Log.i(TAG, "primed watermark=$max")
    }

    private fun pollEvents(ctx: Context) {
        // /events is ordered oldest → newest; stop at the first failed send so
        // ordering is kept and the failed message is retried on the next tick.
        for (e in ViberApi.events(ctx)) {
            if (e.msgId <= watermark) continue
            if (isServiceChat(e.number)) continue
            if (!forward(ctx, e)) return
            ViberAnalysis.enqueue(ctx, e)
            if (e.msgId > watermark) {
                watermark = e.msgId
                save(ctx)
            }
        }
    }

    /** Entry point for the INCOMING broadcast (see ViberEventReceiver). */
    fun handleEventJson(ctx: Context, json: String) {
        try {
            val o = org.json.JSONObject(json)
            val e = ViberApi.Event(
                chatId = o.optLong("chat_id"),
                msgId = o.optLong("msg_id"),
                ts = o.optLong("ts"),
                body = o.optString("body"),
                sender = o.optString("sender").takeIf { it.isNotEmpty() },
                number = o.optString("number").takeIf { it.isNotEmpty() },
                quoteId = o.optLong("quote_id"),
                quoteText = o.optString("quote_text").takeIf { it.isNotEmpty() },
            )
            if (e.msgId <= watermark) return
            if (isServiceChat(e.number)) return
            if (forward(ctx, e)) {
                watermark = maxOf(watermark, e.msgId)
                save(ctx)
                ViberAnalysis.enqueue(ctx, e)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handleEventJson failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Viber's own service/subscribe bot has number "Viber" — not the owner's traffic. */
    private fun isServiceChat(number: String?): Boolean = number == null || number == "Viber"

    /** Builds the rich message and hands it to Telegram. True = accepted. */
    private fun forward(ctx: Context, e: ViberApi.Event): Boolean {
        val who = buildString {
            if (!e.sender.isNullOrBlank()) append(e.sender)
            if (!e.number.isNullOrBlank()) {
                if (isNotEmpty()) append(" ")
                append(e.number)
            }
        }.ifBlank { "Viber" }

        val isMedia = e.body.startsWith("content://")
        val body = if (isMedia) "" else e.body

        // Quoted/reply messages: the api build v23 does not expose quote
        // metadata yet — render it once the provider ships quote_id/quote_text
        // (the text may come filled; otherwise look the quoted message up).
        val quote = e.quoteText ?: if (e.quoteId > 0)
            ViberApi.messages(ctx, e.chatId, 50).firstOrNull { it.id == e.quoteId }
                ?.let { m -> m.body.takeIf { !it.startsWith("content://") } ?: "[медиа]" }
            else null

        val attach = ArrayList<Pair<String, java.io.File>>()
        if (isMedia) {
            // Events carry no extra_uri — look the message up to get the file URI.
            val msg = ViberApi.messages(ctx, e.chatId, 50).firstOrNull { it.id == e.msgId }
            val file = msg?.let { ViberReader.exportMedia(ctx, it) }
            if (file != null) attach.add("m1" to file)
        }

        val md = buildString {
            append("**↙️ Viber** `").append(sdf.format(Date(e.ts))).append("`\n")
            append(who).append('\n')
            if (quote != null) {
                append("\n↪ _в ответ на:_ «").append(quote.take(200)).append("»\n")
            }
            if (body.isNotBlank()) {
                append("\n").append(body.take(1500))
            }
            if (isMedia) {
                append("\n\n![](tg://photo?id=m1)")
                if (attach.isEmpty()) append("\n\n_(медиа недоступно)_")
            }
        }

        return TelegramClient.sendViberAlert(ctx, e.chatId, md, attach).also { ok ->
            Log.i(TAG, "forwarded viber msg #${e.msgId} chat=${e.chatId} media=${attach.isNotEmpty()} ok=$ok")
        }
    }

    private fun save(ctx: Context) {
        prefs(ctx).edit().putLong("viber_fwd_max_id", watermark).apply()
    }
}
