package com.cybermiller.guard

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Long-polls Telegram getUpdates for callback_query (inline buttons) and
 * applies owner's decision to the stored SMS. Runs in GuardService thread.
 */
object TelegramPoller {
    // Injected from gitignored sources (local.properties / CI env) — never hardcode.
    private val TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
    private val CHAT = BuildConfig.TELEGRAM_CHAT_ID
    private const val TAG = "GuardPoller"
    @Volatile private var running = false
    private var offset = 0L

    /** tg chat id -> Viber chat id awaiting an owner reply typed in Telegram. */
    @Volatile private var awaitingReply = mutableMapOf<Long, Long>()

    private fun ownerChatId(): Long? = CHAT.toLongOrNull()

    fun start(ctx: Context) {
        if (running) return
        running = true
        Log.i(TAG, "poller start")
        Thread {
            val appCtx = ctx.applicationContext
            while (running) {
                try {
                    pollOnce(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.javaClass.simpleName}: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    private fun pollOnce(ctx: Context) {
        val url = URL("https://api.telegram.org/bot$TOKEN/getUpdates?timeout=50&offset=$offset")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 60000
        }
        val resp = conn.inputStream.bufferedReader().readText()
        Log.i(TAG, "poll http=${conn.responseCode} offset=$offset")
        val arr = JSONObject(resp).optJSONArray("result") ?: return
        Log.i(TAG, "poll got ${arr.length()} updates")
        for (i in 0 until arr.length()) {
            val upd = arr.getJSONObject(i)
            offset = upd.optLong("update_id", offset) + 1
            val chatId = upd.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")
                ?: upd.optJSONObject("callback_query")?.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")
            if (chatId != ownerChatId()) continue
            val cb = upd.optJSONObject("callback_query")
            if (cb != null) {
                handleCallback(ctx, cb)
            } else {
                handleOwnerMessage(ctx, upd)
            }
        }
    }

    /** Routes a plain message from the owner. If chat is awaiting a Viber reply,
     *  forwards the typed text into Viber through the LiteAPI; otherwise ignores it. */
    private fun handleOwnerMessage(ctx: Context, upd: JSONObject) {
        val msg = upd.optJSONObject("message") ?: return
        val chatId = msg.optJSONObject("chat")?.optLong("id", 0L) ?: return
        if (chatId != ownerChatId()) return
        val text = msg.optString("text", "")
        if (text.startsWith("/start")) {
            TelegramClient.sendText("🛡 Guard бот.\n" +
                "Команды: /start — эта справка.\n" +
                "Кнопки под сообщениями Viber:\n" +
                "• «Открыть в Viber» — открыть чат в приложении Viber\n" +
                "• «Прочитать весь диалог» — выгрузить историю диалога\n" +
                "• «Ответить» — введите следующее сообщение в этом чате, оно уйдёт в Viber штатным движком")
            return
        }
        val viberChatId = awaitingReply.remove(chatId) ?: return
        if (text.isBlank()) return
        if (ViberApi.send(ctx, viberChatId, text)) {
            TelegramClient.sendText("✅ Ответ ушёл в Viber (чат $viberChatId):\n<i>${text.take(400)}</i>")
        } else {
            TelegramClient.sendText("❌ Не удалось отправить ответ в Viber (чат $viberChatId)")
        }
    }

    private fun handleCallback(ctx: Context, cb: JSONObject) {
        val data = cb.optString("data", "")
        Log.i(TAG, "callback data=$data")
        val parts = data.split(":")
        if (parts.size < 2) {
            Log.w(TAG, "callback bad data: $data")
            return
        }
        val action = parts[0]
        val arg = parts[1]
        when (action) {
            "keep" -> {
                val id = arg.toLongOrNull() ?: return
                SmsStore.markHandled(ctx, id, "kept", "owner kept")
                Log.i(TAG, "sms #$id kept by owner")
            }
            "del" -> {
                val id = arg.toLongOrNull() ?: return
                SmsStore.deleteSms(ctx, id)
                Log.i(TAG, "sms #$id deleted by owner")
            }
            "viber" -> {
                ViberSender.openChat(ctx, arg)
                Log.i(TAG, "opened viber chat $arg")
            }
            "viber_open" -> {
                ViberSender.openChat(ctx, arg)
                Log.i(TAG, "opened viber chat $arg")
            }
            "viber_reply" -> {
                val viberChatId = arg.toLongOrNull() ?: return
                awaitingReply[ownerChatId() ?: 0L] = viberChatId
                val title = ViberApi.chats(ctx).firstOrNull { it.chatId == viberChatId }
                    ?.let { it.title + (it.number?.let { n -> " ($n)" } ?: "") } ?: "чат $viberChatId"
                Log.i(TAG, "awaiting reply from owner -> viber chat $viberChatId")
                // ForceReply binds the owner's next message to this prompt, so the
                // reply is unambiguous even if other chat traffic arrives.
                post("sendMessage", JSONObject().apply {
                    put("chat_id", CHAT)
                    put("text", "✏️ Ответ в Viber для <b>$title</b>\n" +
                        "Отправьте текст ответа следующим сообщением.")
                    put("parse_mode", "HTML")
                    put("reply_markup", JSONObject().apply {
                        put("force_reply", true)
                        put("input_field_placeholder", "Текст ответа в Viber…")
                    })
                })
            }
            "viber_read" -> {
                val viberChatId = arg.toLongOrNull() ?: return
                val msgs = readHistory(ctx, viberChatId)
                if (msgs.isEmpty()) {
                    TelegramClient.sendText("❌ Не удалось прочитать историю диалога")
                } else {
                    val phone = ViberApi.chats(ctx).firstOrNull { it.chatId == viberChatId }
                        ?.number ?: "?"
                    sendViberHistory(ctx, msgs, phone)
                }
                Log.i(TAG, "read history for viber chat $viberChatId: ${msgs.size} msgs")
            }
            else -> Log.w(TAG, "callback unknown action: $action")
        }
        val msgId = cb.optJSONObject("message")?.optLong("message_id", 0) ?: 0
        answerCallback(cb.optString("id"), if (action == "del") "Удалено" else "Ок")
        if (action == "del") {
            editMessage(msgId, "🗑 Удалено по решению владельца")
        }
    }

    /** Full history of a chat via /messages paging (before_id), oldest last.
     *  Cap 2000 = provider's max page size; deeper history is not stored
     *  locally by Viber anyway (server keeps only a recent tail per dialog). */
    private fun readHistory(ctx: Context, chatId: Long, cap: Int = 2000): List<ViberApi.Msg> {
        val out = ArrayList<ViberApi.Msg>()
        var before: Long? = null
        while (out.size < cap) {
            val page = ViberApi.messages(ctx, chatId, 200, before)
            if (page.isEmpty()) break
            out.addAll(page)
            before = page.last().id
            if (page.size < 200) break
        }
        return out.reversed() // oldest first
    }

    /** Newest messages always visible; everything older collapses under <details>. */
    private const val HIST_VISIBLE_TAIL = 20
    /** Per-message hard caps of one rich message: 32768 chars, 50 media, 500 blocks. */
    private const val HIST_MAX_CHARS = 30_000
    private const val HIST_MAX_PHOTOS = 50
    private const val HIST_MAX_BLOCKS = 440

    /** Direction markers: ↙️ incoming at the left edge, ↗️ outgoing indented by
     *  invisible Braille blanks (U+2800) so the two columns read apart. */
    private const val DIR_IN = "↙️"
    private const val DIR_OUT = "\u2800\u2800↗️"

    /** One formatted history line; photo rides along, media id patched at render. */
    private data class HistLine(val ts: Long, val md: String, val photo: java.io.File?)

    /**
     * Uploads the whole conversation as ONE rich message whenever it fits the
     * API caps (32768 chars / 50 photos / ~500 blocks): photos embedded in the
     * body at their place in the dialogue, internal Viber content:// URIs never
     * surface. The beginning of the dialog is collapsed under a <details>
     * spoiler ("Ранняя история — ещё N сообщ."), the newest
     * [HIST_VISIBLE_TAIL] messages stay visible. A dialog bigger than the caps
     * splits into several messages, oldest first, each with its own spoiler.
     */
    private fun sendViberHistory(ctx: Context, msgs: List<ViberApi.Msg>, phone: String) {
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val sdfDay = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault())
        val ordered = msgs.sortedBy { it.ts }

        val lines = ArrayList<HistLine>(ordered.size)
        for (m in ordered) {
            val dir = if (m.dir == "in") DIR_IN else DIR_OUT
            val isMedia = m.body.startsWith("content://")
            val text = if (isMedia) "" else m.body
            val stamp = "`" + sdf.format(java.util.Date(m.ts)) + "`"
            if (isMedia) {
                val file = ViberReader.exportMedia(ctx, m)
                if (file != null) {
                    val cap = if (text.isNotBlank()) " " + text.take(300) else ""
                    lines.add(HistLine(m.ts, "$dir $stamp$cap\n\n![](tg://photo?id=@@)\n\n", file))
                } else {
                    val cap = if (text.isNotBlank()) "\n" + text.take(300) else ""
                    lines.add(HistLine(m.ts, "$dir $stamp _(медиа недоступно)_$cap\n\n", null))
                }
            } else {
                val body = if (text.isNotBlank()) text.take(800) else "_(пусто)_"
                lines.add(HistLine(m.ts, "$dir $stamp $body\n\n", null))
            }
        }

        // Split by the hard caps — the only reason a chunk boundary appears.
        val chunks = ArrayList<List<HistLine>>()
        var cur = ArrayList<HistLine>()
        var photos = 0
        var chars = 0
        var blocks = 0
        for (l in lines) {
            val photoCost = if (l.photo != null) 1 else 0
            val cost = l.md.length + 30
            if (photos + photoCost > HIST_MAX_PHOTOS ||
                chars + cost > HIST_MAX_CHARS ||
                blocks + 1 + photoCost > HIST_MAX_BLOCKS) {
                chunks.add(cur); cur = ArrayList(); photos = 0; chars = 0; blocks = 0
            }
            cur.add(l); photos += photoCost; chars += cost; blocks += 1 + photoCost
        }
        if (cur.isNotEmpty()) chunks.add(cur)

        chunks.forEachIndexed { ci, clines ->
            val attach = ArrayList<Pair<String, java.io.File>>()
            val sb = StringBuilder()
            sb.append(if (ci == 0)
                "**📜 История диалога в Viber**\nс `$phone` — ${ordered.size} сообщ. (↙️ вход · \u2800↗️ исход)\n\n"
            else
                "**📜 История диалога в Viber** — продолжение ${ci + 1}/${chunks.size}\n\n")

            val tailN = minOf(HIST_VISIBLE_TAIL, clines.size)
            val older = clines.dropLast(tailN)
            val tail = clines.takeLast(tailN)
            if (older.isNotEmpty()) {
                val from = sdfDay.format(java.util.Date(older.first().ts))
                val to = sdfDay.format(java.util.Date(older.last().ts))
                sb.append("<details><summary>📋 Ранняя история — ещё ${older.size} сообщ.")
                sb.append(if (from != to) " ($from … $to)" else " ($from)")
                sb.append("</summary>\n\n")
                for (l in older) sb.append(renderHistLine(l, attach))
                sb.append("</details>\n\n")
            }
            for (l in tail) sb.append(renderHistLine(l, attach))
            TelegramClient.sendRich(sb.toString(), null, attach)
        }
    }

    /** Registers the photo for upload and substitutes its per-chunk media id. */
    private fun renderHistLine(l: HistLine, attach: ArrayList<Pair<String, java.io.File>>): String {
        if (l.photo == null) return l.md
        attach.add("m${attach.size}" to l.photo)
        val id = attach.last().first
        return l.md.replace("tg://photo?id=@@", "tg://photo?id=$id")
    }

    private fun answerCallback(cbId: String, text: String) {
        val payload = JSONObject().apply {
            put("callback_query_id", cbId); put("text", text)
        }
        post("answerCallbackQuery", payload)
    }

    private fun editMessage(messageId: Long, text: String) {
        if (messageId == 0L) return
        val payload = JSONObject().apply {
            put("chat_id", CHAT); put("message_id", messageId); put("text", text)
        }
        post("editMessageText", payload)
    }

    private fun post(method: String, payload: JSONObject) {
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/$method").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 8000; readTimeout = 15000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText().take(150)
            Log.i(TAG, "$method http=$code $body")
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
