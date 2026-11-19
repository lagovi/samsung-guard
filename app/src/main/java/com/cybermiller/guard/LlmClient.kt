package com.cybermiller.guard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Verdict(val verdict: String, val explanation: String, val severity: String)

object LlmClient {
    private const val TAG = "GuardLlm"
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
            Log.i(TAG, "LLM request for $address")
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
                connectTimeout = 15000; readTimeout = 60000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: ""
            Log.i(TAG, "LLM http=$code resp=${resp.take(200)}")
            if (code !in 200..299) {
                return Verdict("keep", "HTTP $code: ${resp.take(120)}", "low")
            }
            val json = JSONObject(resp)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(TAG, "LLM returned no choices: ${resp.take(300)}")
                return Verdict("keep", "LLM empty response", "low")
            }
            val content = choices.getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            if (content.isBlank()) {
                Log.w(TAG, "LLM content empty: ${resp.take(300)}")
                return Verdict("keep", "LLM empty content", "low")
            }
            val v = JSONObject(extractJson(content))
            val rawVerdict = v.optString("verdict", "keep").lowercase().trim()
            // normalize model's vocabulary to our three buckets
            val verdict = when {
                rawVerdict.contains("delete") || rawVerdict.contains("spam") ||
                    rawVerdict.contains("junk") || rawVerdict.contains("trash") -> "delete"
                rawVerdict.contains("alert") || rawVerdict.contains("important") ||
                    rawVerdict.contains("urgent") || rawVerdict.contains("attention") -> "alert"
                else -> "keep"
            }
            Verdict(
                verdict = verdict,
                explanation = v.optString("explanation", ""),
                severity = v.optString("severity", "low").lowercase(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "LLM error", e)
            Verdict("keep", "LLM error: ${e.javaClass.simpleName}: ${e.message}", "low")
        }
    }

    /**
     * Strips markdown code fences / prose and returns the first JSON object substring.
     */
    private fun extractJson(s: String): String {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) return s.substring(start, end + 1)
        return s
    }
}