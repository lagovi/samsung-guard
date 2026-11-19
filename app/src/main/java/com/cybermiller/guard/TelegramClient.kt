package com.cybermiller.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object TelegramClient {
    private const val TOKEN = "REDACTED-ROTATED-TOKEN"
    private const val CHAT = "000000000"

    fun sendSmsAlert(ctx: Context, smsId: Long, address: String, body: String, v: Verdict) {
        val icon = when (v.severity) {
            "high" -> "🚨"
            "med" -> "⚠️"
            else -> "📩"
        }
        val text = "$icon <b>SMS</b> from <code>$address</code>\n\n" +
                "<i>${body.take(500)}</i>\n\n" +
                "Вердикт: <b>${v.verdict.uppercase()}</b> — ${v.explanation}"
        val kb = JSONArray().apply {
            put(JSONArray().apply {
                put(button("✅ Оставить", "keep:$smsId"))
                put(button("🗑 Удалить", "del:$smsId"))
            })
        }
        val payload = JSONObject().apply {
            put("chat_id", CHAT)
            put("text", text)
            put("parse_mode", "HTML")
            put("reply_markup", JSONObject().put("inline_keyboard", kb))
        }
        httpPost("sendMessage", payload)
    }

    fun sendText(text: String) {
        val payload = JSONObject().apply {
            put("chat_id", CHAT)
            put("text", text)
            put("parse_mode", "HTML")
        }
        httpPost("sendMessage", payload)
    }

    private fun button(label: String, cb: String): JSONObject =
        JSONObject().put("text", label).put("callback_data", cb)

    private fun httpPost(method: String, payload: JSONObject) {
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/$method")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000; readTimeout = 12000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            // offline: caller should retry later
        }
    }
}