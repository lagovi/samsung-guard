package com.cybermiller.guard

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * LLM analysis of incoming Viber conversations (prototype stage).
 *
 * Messages are NOT analysed one by one — clients type in bursts and a
 * recommendation per fragment is noise. Incoming messages buffer per chat;
 * the buffer flushes when the chat has been quiet for QUIET_MS (every new
 * message re-arms the timer) or when MAX_WAIT_MS passed since the first
 * buffered message, whichever comes first. One LLM call per batch with a
 * short dialog tail as context; the assessment/recommendation is posted to
 * the owner's Telegram chat as a separate 🤖 message. Nothing here ever
 * sends anything to the client.
 *
 * When the key is missing or the LLM is unreachable the regular forwarding
 * (ViberMonitor) is unaffected — analysis is skipped / reported briefly.
 *
 * TODO(next stages): feed CRM data and existing client layouts into the
 *  client-data section of the prompt.
 */
object ViberAnalysis {
    private const val TAG = "GuardViberAn"

    /** The built-in system prompt — shown/edited by the owner via /prompt. */
    fun defaultPrompt(): String = SYS_PROMPT
    private const val QUIET_MS = 35_000L        // silence that ends a burst
    private const val MAX_WAIT_MS = 180_000L    // hard ceiling since the first msg
    private const val MAX_BATCH = 12            // flush immediately past this
    private const val TAIL = 15                 // context messages from /messages
    // While the contact is typing (Viber api v24 exposes a ~6 s window in
    // /chats) the flush is postponed and re-checked every TYPE_HOLD_MS —
    // so the analysis always sees the finished thought. The hold itself is
    // capped: past MAX_HOLD_MS since the first message we flush regardless.
    private const val TYPE_HOLD_MS = 8_000L
    private const val MAX_HOLD_MS = 300_000L

    private class Buf(val firstTs: Long) {
        val msgs = ArrayList<ViberApi.Event>()
        var task: ScheduledFuture<*>? = null
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "viber-analysis").apply { isDaemon = true }
    }
    private val bufs = HashMap<Long, Buf>()
    private val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    @Volatile private var keyMissingLogged = false

    /** Called by ViberMonitor after a message was successfully forwarded. */
    fun enqueue(ctx: Context, e: ViberApi.Event) {
        if (!LlmClient.available()) {
            if (!keyMissingLogged) {
                keyMissingLogged = true
                Log.i(TAG, "LLM_API_KEY empty — Viber analysis disabled")
            }
            return
        }
        val appCtx = ctx.applicationContext
        synchronized(bufs) {
            val now = System.currentTimeMillis()
            val b = bufs.getOrPut(e.chatId) { Buf(e.ts) }
            b.msgs.add(e)
            b.task?.cancel(false)
            // Flush right away past the batch cap; otherwise wait for QUIET_MS
            // but never longer than MAX_WAIT_MS since the first message.
            val delay = if (b.msgs.size >= MAX_BATCH) 0L
                else (b.firstTs + MAX_WAIT_MS - now).coerceAtMost(QUIET_MS).coerceAtLeast(0L)
            val gen = b.msgs.size
            b.task = scheduler.schedule({ flushIfCurrent(appCtx, e.chatId, gen) },
                delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Flushes only if no newer message re-armed the buffer meanwhile. */
    private fun flushIfCurrent(ctx: Context, chatId: Long, gen: Int) {
        val batch: List<ViberApi.Event>
        synchronized(bufs) {
            val b = bufs[chatId] ?: return
            if (b.msgs.size != gen) return
            // Hold while the contact is typing (unless the hold cap expired).
            val holdExpired = System.currentTimeMillis() - b.firstTs >= MAX_HOLD_MS
            if (!holdExpired && isTyping(ctx, chatId)) {
                b.task?.cancel(false)
                b.task = scheduler.schedule({ flushIfCurrent(ctx, chatId, gen) },
                    TYPE_HOLD_MS, TimeUnit.MILLISECONDS)
                Log.i(TAG, "chat $chatId is typing — holding flush")
                return
            }
            batch = ArrayList(b.msgs)
            b.msgs.clear()
            b.task = null
        }
        try {
            analyze(ctx, chatId, batch)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** True when the peer of `chatId` typed within the last few seconds. */
    private fun isTyping(ctx: Context, chatId: Long): Boolean =
        ViberApi.chats(ctx).firstOrNull { it.chatId == chatId }?.typing == true

    private const val SYS_PROMPT = """Ты — помощник владельца рекламного агентства. Он не читает
переписку целиком — ты делаешь первичный разбор новых входящих сообщений клиентов в Viber.

Задача по НОВЫМ сообщениям:
1. Оценка: что происходит — чего хочет или о чём спрашивает клиент, есть ли срочность,
   хватает ли информации, чтобы ответить.
2. Рекомендация по дальнейшим действиям, если она уместна: что ответить (по смыслу, не дословно),
   какую информацию запросить, нужно ли вмешаться срочно или можно подождать.
   Если никакие действия не требуются — верни пустую рекомендацию.

Отвечай ТОЛЬКО JSON без markdown-обёртки:
{"assessment":"1-3 предложения по-русски","recommendation":"рекомендация по-русски или пустая строка","urgency":"low|med|high"}

Не выдумывай факты. Если данных мало — в рекомендации скажи, что именно уточнить."""

    private fun analyze(ctx: Context, chatId: Long, batch: List<ViberApi.Event>) {
        val sysPrompt = PromptStore.get(ctx, PromptStore.VIBER, SYS_PROMPT)
        val contact = ViberApi.contactByChat(ctx, chatId)
        val who = contact?.displayName
            ?: batch.first().sender?.takeIf { it.isNotBlank() }
            ?: batch.first().number
            ?: "чат $chatId"

        // Dialog tail for context, oldest → newest (includes the new messages).
        val tail = ViberApi.messages(ctx, chatId, TAIL).reversed()
        val tailSb = StringBuilder()
        for (m in tail) {
            val role = if (m.dir == "out") "владелец" else "клиент"
            val text = if (m.body.startsWith("content://")) "[медиа]" else m.body
            val quote = m.quoteText?.let { " (в ответ на «${it.take(80)}»)" } ?: ""
            tailSb.append('[').append(sdf.format(Date(m.ts))).append("] ").append(role)
                .append(quote).append(": ").append(text.ifBlank { "(пусто)" }.take(500)).append('\n')
        }

        val newSb = StringBuilder()
        for (e in batch) {
            val text = if (e.body.startsWith("content://")) "[медиа]" else e.body
            newSb.append("- [").append(sdf.format(Date(e.ts))).append("] ")
                .append(text.ifBlank { "(пусто)" }.take(500)).append('\n')
        }

        val user = buildString {
            append("Контакт: ${contact?.displayName ?: who}")
            contact?.number?.let { append(", номер $it") }
            append("\nДанные о клиенте (CRM, макеты): пока не подключены.\n\n")
            append("=== Недавний диалог (контекст) ===\n").append(tailSb).append('\n')
            append("=== НОВЫЕ сообщения (разбирай их) ===\n").append(newSb)
        }
        Log.i(TAG, "analyzing chat=$chatId new=${batch.size} tail=${tail.size}")

        val content = LlmClient.chat(sysPrompt, user, maxTokens = 700)
        if (content.isNullOrBlank()) {
            TelegramClient.sendText(
                "⚠️ Не удалось проанализировать входящие Viber («$who», ${batch.size} сообщ.) — LLM недоступен")
            return
        }

        val v = try {
            val s = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)
            org.json.JSONObject(s)
        } catch (t: Throwable) {
            Log.w(TAG, "bad LLM json: ${content.take(200)}")
            // Still show something — better a raw reply than silence.
            TelegramClient.sendRich("**🤖 Анализ Viber** — $who\n\n${content.take(1500)}", null)
            return
        }

        val assessment = v.optString("assessment", "").trim()
        val recommendation = v.optString("recommendation", "").trim()
        val urgency = v.optString("urgency", "low").lowercase().trim()
        val badge = when {
            urgency.startsWith("high") || urgency.startsWith("выс") -> "🔴 высокая"
            urgency.startsWith("med") || urgency.startsWith("ср") -> "🟡 средняя"
            else -> "🟢 низкая"
        }

        val md = buildString {
            append("**🤖 Анализ Viber** — $who · новых: ${batch.size}\n\n")
            if (assessment.isNotBlank()) append("**Оценка:** $assessment\n")
            append("**Рекомендация:** ").append(recommendation.ifBlank { "— действий не требуется" }).append('\n')
            append("Срочность: $badge")
        }
        TelegramClient.sendRich(md, null)
        Log.i(TAG, "analysis sent chat=$chatId urgency=$urgency rec=${recommendation.isNotBlank()}")
    }
}
