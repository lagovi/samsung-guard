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
    private const val TOKEN = "REDACTED-ROTATED-TOKEN"
    private const val CHAT = "000000000"
    private const val TAG = "GuardPoller"
    @Volatile private var running = false
    private var offset = 0L

    /** chat_id -> phone number awaiting an owner reply typed in Telegram. */
    @Volatile private var awaitingReply = mutableMapOf<Long, String>()

    /** Sets the phone number the next owner message in `chat` will be sent to in Viber. */
    fun awaitReply(chatId: Long, phone: String) {
        awaitingReply[chatId] = phone
        Log.i(TAG, "awaiting reply in chat $chatId -> $phone")
    }

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
            if (chatId != CHAT.toLong()) continue
            val cb = upd.optJSONObject("callback_query")
            if (cb != null) {
                handleCallback(ctx, cb)
            } else {
                handleOwnerMessage(ctx, upd)
            }
        }
    }

    /** Routes a plain message from the owner. If chat is awaiting a Viber reply,
     *  forwards the typed text into Viber; otherwise ignores it. */
    private fun handleOwnerMessage(ctx: Context, upd: JSONObject) {
        val msg = upd.optJSONObject("message") ?: return
        val chatId = msg.optJSONObject("chat")?.optLong("id", 0L) ?: return
        if (chatId != CHAT.toLong()) return
        val text = msg.optString("text", "")
        if (text.startsWith("/start")) {
            TelegramClient.sendText("🛡 Guard бот.\n" +
                "Команды: /start — эта справка.\n" +
                "Кнопки под сообщениями Viber:\n" +
                "• «Открыть в Viber» — открыть чат в приложении Viber\n" +
                "• «Прочитать весь диалог» — выгрузить всю историю из БД Viber\n" +
                "• «Ответить» — введите следующее сообщение в этом чате, оно отправится в Viber от вашего номера")
            return
        }
        val phone = awaitingReply.remove(chatId) ?: return
        if (text.isBlank()) return
        try {
            ViberSender.sendReply(ctx, phone, text)
            TelegramClient.sendText("✅ Ответ отправлен в Viber ($phone):\n<i>${text.take(400)}</i>")
        } catch (e: Throwable) {
            TelegramClient.sendText("❌ Не удалось отправить ответ в Viber: ${e.message}")
            Log.w(TAG, "sendReply failed", e)
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
                awaitingReply[CHAT.toLong()] = arg
                Log.i(TAG, "awaiting reply from owner -> $arg")
                // ForceReply binds the owner's next message to this prompt, so the
                // reply is unambiguous even if other chat traffic arrives. The phone
                // number travels in the quoted prompt text, so state also survives
                // a service restart.
                post("sendMessage", JSONObject().apply {
                    put("chat_id", CHAT)
                    put("text", "✏️ Ответ в Viber для <code>$arg</code>\n" +
                        "Отправьте текст ответа следующим сообщением.")
                    put("parse_mode", "HTML")
                    put("reply_markup", JSONObject().apply {
                        put("force_reply", true)
                        put("input_field_placeholder", "Текст ответа в Viber…")
                    })
                })
            }
            "viber_read" -> {
                val conversationId = arg.toLongOrNull() ?: return
                ViberReader.readAllMessages(ctx, conversationId)?.let { msgs ->
                    val phone = ViberReader.participantNumber(ctx, 2L) ?: "?"
                    sendViberHistory(ctx, msgs, phone)
                } ?: run {
                    TelegramClient.sendText("❌ Не удалось прочитать историю диалога")
                }
                Log.i(TAG, "read all messages for conv $conversationId")
            }
            else -> Log.w(TAG, "callback unknown action: $action")
        }
        val msgId = cb.optJSONObject("message")?.optLong("message_id", 0) ?: 0
        answerCallback(cb.optString("id"), if (action == "del") "Удалено" else "Ок")
        if (action == "del") {
            editMessage(msgId, "🗑 Удалено по решению владельца")
        }
    }

    /**
     * Uploads the whole conversation as rich messages in chronological order.
     * Photos are embedded in the message body (tg://photo?id=) instead of being
     * sent as separate documents; internal Viber content:// URIs never surface.
     *
     * Messages are packed into batches (≤ 10 photos) so a 100-message history
     * does not turn into 100 separate Telegram posts.
     */
    private fun sendViberHistory(ctx: Context, msgs: List<ViberReader.Msg>, phone: String) {
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT)
        val ordered = msgs.sortedBy { it.ts }

        val header = "**📜 История диалога в Viber**\nс `$phone` — ${ordered.size} сообщ.\n\n"
        var chunk = StringBuilder(header)
        var attach = ArrayList<Pair<String, java.io.File>>()
        var marked = false

        fun flush() {
            if (chunk.isBlank()) return
            if (attach.isEmpty()) {
                TelegramClient.sendRich(chunk.toString())
            } else {
                TelegramClient.sendRich(chunk.toString(), null, attach)
            }
            chunk = StringBuilder()
            attach = ArrayList()
        }

        for (m in ordered) {
            val dir = if (m.incoming) "⬅️" else "➡️"
            val isMedia = m.mime != 0
            val text = if (isMedia && m.body.startsWith("content://")) "" else m.body

            if (isMedia) {
                val file = ViberReader.exportMedia(ctx, m.mediaUri, m.mediaFileName, m.mediaSize)
                if (file != null) {
                    // Batch cap: 10 photos per rich message.
                    if (attach.size >= 10) flush()
                    val id = "m${attach.size}"
                    attach.add(id to file)
                    chunk.append("$dir `").append(sdf.format(java.util.Date(m.ts))).append("` ")
                    if (text.isNotBlank()) chunk.append(text.take(300)).append(" ")
                    chunk.append("![](tg://photo?id=$id)\n\n")
                } else {
                    if (attach.isNotEmpty()) flush()
                    chunk.append("$dir `").append(sdf.format(java.util.Date(m.ts)))
                        .append("` _(медиа недоступно)_")
                    if (text.isNotBlank()) chunk.append("\n").append(text.take(300))
                    chunk.append("\n\n")
                }
            } else {
                if (attach.isNotEmpty()) flush()
                chunk.append("$dir `").append(sdf.format(java.util.Date(m.ts))).append("` ")
                chunk.append(if (text.isNotBlank()) text.take(800) else "_(пусто)_")
                chunk.append("\n\n")
            }

            if (chunk.length > 28_000) flush()   // rich message limit is 32768 chars
            marked = true
        }
        if (marked) flush()
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