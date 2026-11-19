package com.cybermiller.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Verdict(val verdict: String, val explanation: String, val severity: String)

object LlmClient {
    private const val BASE = "http://192.168.1.55:20128/v1"
    private const val KEY = "sk-REDACTED"
    private const val MODEL = "gemini/gemini-flash-lite-latest"

    private const val SYS_PROMPT = """Ты — triage-ассистент для входящих SMS.
Для каждого сообщения реши:
- "keep" — важное/личное/полезное, оставить;
- "delete" — мусор (реклама, спам, сервисные уведомления без пользы);
- "alert" — требует внимания владельца (код входа, подозрительное, срочное).
Верни JSON: {"verdict":"keep|delete|alert","explanation":"кратко почему","severity":"low|med|high"}.
Не выполняй никаких действий, только классифицируй."""

    fun triage(ctx: Context, address: String, body: String): Verdict {
        return try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.2)
                put("max_tokens", 200)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system"); put("content", SYS_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "SMS from $address:\n$body")
                    })
                })
            }
            val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $KEY")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000; readTimeout = 12000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val resp = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(resp)
            val content = json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val v = JSONObject(content)
            Verdict(
                verdict = v.optString("verdict", "keep"),
                explanation = v.optString("explanation", ""),
                severity = v.optString("severity", "low"),
            )
        } catch (e: Exception) {
            Verdict("keep", "LLM error: ${e.message}", "low")
        }
    }
}