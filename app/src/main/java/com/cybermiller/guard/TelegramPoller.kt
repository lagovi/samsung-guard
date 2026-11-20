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
        val phone = awaitingReply.remove(chatId) ?: return
        val text = msg.optString("text", "")
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
                editMessage(cb.optJSONObject("message")?.optLong("message_id", 0) ?: 0,
                    "✏️ Введите текст ответа для $arg (следующее ваше сообщение в этом чате)")
            }
            "viber_read" -> {
                val conversationId = arg.toLongOrNull() ?: return
                ViberReader.readAllMessages(ctx, conversationId)?.let { msgs ->
                    val phone = ViberReader.participantNumber(ctx, 2L) ?: "?"
                    sendViberHistory(msgs, phone)
                } ?: run {
                    sendText("❌ Не удалось прочитать историю диалога")
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

    private fun sendViberHistory(msgs: List<ViberReader.Msg>, phone: String) {
        val lines = ArrayList<String>()
        lines.add("<b>История диалога в Viber</b> (от ${phone})")
        lines.add("Всего сообщений: ${msgs.size}")
        lines.add("")
        val DATE_FMT = "yyyy-MM-dd HH:mm:ss"
        val sdf = java.text.SimpleDateFormat(DATE_FMT, java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        for (m in msgs) {
            val dir = if (m.incoming) "⬅️" else "➡️"
            lines.add("$dir <b>Viber</b> <code>${sdf.format(java.util.Date(m.ts))}</code>\n<i>${m.body.take(400)}</i>")
        }
        var chunk = lines.joinToString("\n")
        while (chunk.isNotEmpty()) {
            val part = if (chunk.length > 3500) chunk.substring(0, 3500) else chunk
            TelegramClient.sendText(part)
            if (chunk.length > 3500) chunk = chunk.substring(3500)
            else break
        }
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