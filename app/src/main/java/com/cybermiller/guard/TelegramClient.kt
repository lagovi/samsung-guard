package com.cybermiller.guard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object TelegramClient {
    // Injected from gitignored sources (local.properties / CI env) — never hardcode.
    private val TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
    private val CHAT = BuildConfig.TELEGRAM_CHAT_ID

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
                put(button("💬 В Viber", "viber:$address"))
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

    /** Sends a Viber message as a rich message: photos from the conversation are
     *  embedded in the message body (not attached as separate documents) and the
     *  inline buttons sit underneath.
     *
     *  `body` is rich markdown that may reference uploaded media as
     *  `![](tg://photo?id=mN)`, with `attach` holding the matching (mN, file) pairs. */
    fun sendViberAlert(
        ctx: Context,
        chatId: Long,
        body: String,
        attach: List<Pair<String, java.io.File>> = emptyList()
    ): Boolean {
        val kb = JSONArray().apply {
            put(JSONArray().apply {
                put(button("📜 Прочитать весь диалог", "viber_read:$chatId"))
                put(button("✏️ Ответить", "viber_reply:$chatId"))
            })
        }
        return sendRich(body, kb, attach)
    }

    private fun sendMessageWithMarkup(text: String, kb: JSONArray) {
        val payload = JSONObject().apply {
            put("chat_id", CHAT)
            put("text", text)
            put("parse_mode", "HTML")
            put("reply_markup", JSONObject().put("inline_keyboard", kb))
        }
        httpPost("sendMessage", payload)
    }

    private fun sendFileWithMarkup(file: java.io.File, caption: String, kb: JSONArray) {
        val boundary = "----GuardBoundary${System.currentTimeMillis()}"
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/sendDocument")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                connectTimeout = 15000; readTimeout = 60000
            }
            conn.outputStream.use { os ->
                fun field(name: String, value: String) {
                    os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray())
                }
                field("chat_id", CHAT)
                if (caption.isNotEmpty()) field("caption", caption.take(1000))
                field("parse_mode", "HTML")
                field("reply_markup", JSONObject().put("inline_keyboard", kb).toString())
                os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; " +
                    "filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            Log.i("GuardTg", "sendDocumentWithMarkup(${file.name}) http=$code")
        } catch (e: Exception) {
            Log.w("GuardTg", "sendDocumentWithMarkup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun sendText(text: String) {
        val payload = JSONObject().apply {
            put("chat_id", CHAT)
            put("text", text)
            put("parse_mode", "HTML")
        }
        httpPost("sendMessage", payload)
    }

    /** Sends an image (e.g. the /health graph PNG) with an optional caption. */
    fun sendPhoto(file: java.io.File, caption: String = "") {
        val boundary = "----GuardBoundary${System.currentTimeMillis()}"
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/sendPhoto")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                connectTimeout = 15000; readTimeout = 60_000
            }
            conn.outputStream.use { os ->
                fun field(name: String, value: String) {
                    os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray())
                }
                field("chat_id", CHAT)
                if (caption.isNotEmpty()) field("caption", caption.take(1000))
                os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; " +
                    "filename=\"${file.name}\"\r\nContent-Type: image/png\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(200) ?: ""
            Log.i("GuardTg", "sendPhoto(${file.name}) http=$code $body")
        } catch (e: Exception) {
            Log.w("GuardTg", "sendPhoto failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Registers the bot menu (Telegram client's menu button). Idempotent. */
    fun setMyCommands() {
        val payload = JSONObject().apply {
            put("commands", JSONArray().apply {
                put(JSONObject().put("command", "start")
                    .put("description", "Справка"))
                put(JSONObject().put("command", "health")
                    .put("description", "Состояние телефона: текущие значения + графики"))
            })
        }
        httpPost("setMyCommands", payload)
    }

    /** Uploads a local file (photo/video/document) to the owner chat. */
    fun sendFile(file: java.io.File, caption: String = "") {
        val boundary = "----GuardBoundary${System.currentTimeMillis()}"
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/sendDocument")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                connectTimeout = 15000; readTimeout = 60000
            }
            conn.outputStream.use { os ->
                fun field(name: String, value: String) {
                    os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray())
                }
                field("chat_id", CHAT)
                if (caption.isNotEmpty()) field("caption", caption.take(1000))
                field("parse_mode", "HTML")
                os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; " +
                    "filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(200) ?: ""
            Log.i("GuardTg", "sendDocument(${file.name}) http=$code $body")
        } catch (e: Exception) {
            Log.w("GuardTg", "sendDocument failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Sends a rich message with embedded media: one Telegram message whose body
     * contains the photos inline (Bot API 10.1+ `sendRichMessage`).
     *
     * `markdown` is the rich-markdown body; `attach` is a list of (name, file)
     * pairs uploaded as `attach://<name>` and referenced from the markdown via
     * `![](tg://photo?id=<name> "caption")`. `kb` is the inline keyboard rows
     * array — wrapped here into the InlineKeyboardMarkup object Telegram needs.
     *
     * Returns true when Telegram accepted the message (callers use this to
     * decide whether to advance their dedup watermark).
     */
    fun sendRich(
        markdown: String,
        kb: JSONArray? = null,
        attach: List<Pair<String, java.io.File>> = emptyList()
    ): Boolean {
        val boundary = "----GuardBoundary${System.currentTimeMillis()}"
        try {
            val conn = (URL("https://api.telegram.org/bot$TOKEN/sendRichMessage")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                connectTimeout = 15000; readTimeout = 120_000
            }
            val richJson = JSONObject().apply {
                put("markdown", markdown)
                if (attach.isNotEmpty()) put("media", JSONArray().apply {
                    for ((name, _) in attach) put(JSONObject().apply {
                        put("id", name)
                        put("media", JSONObject().apply {
                            put("type", "photo")
                            put("media", "attach://$name")
                        })
                    })
                })
            }

            conn.outputStream.use { os ->
                fun field(name: String, value: String) {
                    os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray())
                }
                field("chat_id", CHAT)
                field("rich_message", richJson.toString())
                if (kb != null) {
                    field("reply_markup", JSONObject().put("inline_keyboard", kb).toString())
                }
                for ((name, file) in attach) {
                    os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; " +
                        "filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
                    file.inputStream().use { it.copyTo(os) }
                    os.write("\r\n".toByteArray())
                }
                os.write(("--$boundary--\r\n").toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(200) ?: ""
            Log.i("GuardTg", "sendRichMessage http=$code $body")
            return code in 200..299 && body.contains("\"ok\":true")
        } catch (e: Exception) {
            Log.w("GuardTg", "sendRichMessage failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
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
                connectTimeout = 15000; readTimeout = 30000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(200) ?: ""
            Log.i("GuardTg", "$method http=$code $body")
        } catch (e: Exception) {
            Log.w("GuardTg", "$method failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}