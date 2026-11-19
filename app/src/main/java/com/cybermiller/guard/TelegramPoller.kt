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
            val cb = upd.optJSONObject("callback_query") ?: continue
            handleCallback(ctx, cb)
        }
    }

    private fun handleCallback(ctx: Context, cb: JSONObject) {
        val data = cb.optString("data", "")
        Log.i(TAG, "callback data=$data")
        val parts = data.split(":")
        if (parts.size != 2) {
            Log.w(TAG, "callback bad data: $data")
            return
        }
        val action = parts[0]
        val id = parts[1].toLongOrNull()
        if (id == null) {
            Log.w(TAG, "callback bad id: $data")
            return
        }
        when (action) {
            "keep" -> {
                SmsStore.markHandled(ctx, id, "kept", "owner kept")
                Log.i(TAG, "sms #$id kept by owner")
            }
            "del" -> {
                SmsStore.deleteSms(ctx, id)
                Log.i(TAG, "sms #$id deleted by owner")
            }
            "viber" -> {
                val num = parts[1]
                ViberSender.openChat(ctx, num)
                Log.i(TAG, "opened viber chat $num")
            }
            else -> Log.w(TAG, "callback unknown action: $action")
        }
        val msgId = cb.optJSONObject("message")?.optLong("message_id", 0) ?: 0
        answerCallback(cb.optString("id"), if (action == "del") "Удалено" else "Оставлено")
        editMessage(msgId, if (action == "del") "🗑 Удалено по решению владельца" else "✅ Оставлено")
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