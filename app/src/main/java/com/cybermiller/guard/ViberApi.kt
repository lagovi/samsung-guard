package com.cybermiller.guard

import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the internal LiteAPI of the custom Viber "api" build
 * (ContentProvider `com.viber.voip.liteapi`).
 *
 * Access rule of that provider: the calling package must be signed with the
 * same debug key as the Viber build (uber-apk-signer debug key) — Guard is
 * signed with exactly that key, so no root is involved here. root/shell uid
 * also have access, which is what makes `adb shell content …` work for
 * debugging.
 *
 * Every response is one cursor row in the `json` column, envelope:
 *   {"ok":true,"data":[...]}   or   {"ok":false,"error":"..."}
 */
object ViberApi {
    private const val TAG = "GuardViberApi"
    private const val BASE = "content://com.viber.voip.liteapi"

    /** Chat list item from /chats. */
    data class Chat(
        val chatId: Long,
        val type: Int,        // 0 = personal (has number), 6 = channel/notes
        val title: String,
        val number: String?,  // peer phone for personal chats
        val lastTs: Long,
        val lastText: String?,
        val lastDir: String?, // "in"/"out"
        val unread: Int,
        // Typing status (api build v24+; absent on older builds → 0/false).
        val typingTs: Long,   // ms of the last "typing" notification
        val typing: Boolean,  // true = typed within the last ~6 s
    )

    /** Message from /messages. */
    data class Msg(
        val id: Long,
        val dir: String,      // "in"/"out"
        val status: Int,      // 1 = in flight, 2 = delivered
        val ts: Long,
        val body: String,     // text; for media a thumbnail content-URI
        val sender: String?,
        val senderNumber: String?,
        val extraUri: String?,// full media location (often a MediaStore URI)
        // Quote/reply metadata — planned for the api build (v24+); absent
        // fields come back as 0/null until the provider exposes them.
        val quoteId: Long = 0L,
        val quoteText: String? = null,
    )

    /** Incoming event from /events or the INCOMING broadcast. */
    data class Event(
        val chatId: Long,
        val msgId: Long,
        val ts: Long,
        val body: String,
        val sender: String?,
        val number: String?,
        val quoteId: Long = 0L,
        val quoteText: String? = null,
    )

    /** Contact card from /contact (provider v22+). */
    data class Contact(
        val id: Long,             // contact id (photo URIs reference this)
        val number: String?,
        val displayName: String?,
        val photoAvailable: Boolean,
        val photoMime: String?,
        val photoUri: String?,
        val chatId: Long?,
    )

    /** Contact card lookup by chat id (build v22+; null on older builds). */
    fun contactByChat(ctx: Context, chatId: Long): Contact? {
        val arr = query(ctx, "$BASE/contact?chat_id=$chatId") ?: run {
            Log.i(TAG, "contact chat=$chatId: query returned null (older build?)")
            return null
        }
        val o = elements(arr).firstOrNull() ?: run {
            Log.i(TAG, "contact chat=$chatId: empty data")
            return null
        }
        val c = Contact(
            id = o.optLong("id"),
            number = o.optString("number").takeIf { it.isNotEmpty() },
            displayName = o.optString("display_name").takeIf { it.isNotEmpty() },
            photoAvailable = o.optBoolean("photo_available"),
            photoMime = o.optString("photo_mime").takeIf { it.isNotEmpty() },
            photoUri = o.optString("photo_uri").takeIf { it.isNotEmpty() },
            chatId = if (o.has("chat_id")) o.optLong("chat_id") else null,
        )
        Log.i(TAG, "contact chat=$chatId -> id=${c.id} photo=${c.photoAvailable} uri=${c.photoUri}")
        return c
    }

    /**
     * Normalises `data` array elements. /chats and /messages return nested
     * objects, but /events returns each element as a JSON-encoded string —
     * accept both shapes.
     */
    private fun elements(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v
                is String -> try { JSONObject(v) } catch (_: Exception) { null }
                else -> null
            }
        }

    private fun query(ctx: Context, uri: String): JSONArray? {
        // Right after an install or a Viber process death AMS may not have
        // (re)published the provider yet: ContentResolver.query then returns
        // a NULL cursor without any exception. Retry briefly before giving up.
        for (attempt in 1..3) {
            val c = try {
                ctx.contentResolver.query(android.net.Uri.parse(uri), arrayOf("json"), null, null, null)
            } catch (e: SecurityException) {
                Log.w(TAG, "provider rejected us (signature mismatch?): ${e.message}")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "query $uri failed: ${e.javaClass.simpleName}: ${e.message}")
                return null
            }
            if (c == null) {
                Log.w(TAG, "query $uri: provider not published yet (attempt $attempt/3)")
                if (attempt < 3) try { Thread.sleep(1500L) } catch (_: InterruptedException) { return null }
                continue
            }
            return c.use { cur ->
                if (!cur.moveToFirst()) {
                    Log.w(TAG, "$uri: empty cursor from a published provider")
                    null
                } else {
                    val env = JSONObject(cur.getString(cur.getColumnIndexOrThrow("json")))
                    if (!env.optBoolean("ok", false)) {
                        Log.w(TAG, "$uri -> error: ${env.optString("error")}")
                        null
                    } else when (val d = env.opt("data")) {
                        // /contact (single card) returns data as a JSON object,
                        // /chats, /messages, /contacts, /events return an array.
                        is JSONArray -> d
                        is JSONObject -> JSONArray().put(d)
                        else -> null
                    }
                }
            }
        }
        return null
    }

    private fun call(ctx: Context, method: String, arg: String?, extras: Bundle): JSONObject? {
        return try {
            val b = ctx.contentResolver.call(android.net.Uri.parse("$BASE/chats"), method, arg, extras)
                ?: return null
            val env = JSONObject(b.getString("json"))
            if (!env.optBoolean("ok", false)) {
                Log.w(TAG, "call $method -> error: ${env.optString("error")}")
            }
            env
        } catch (e: SecurityException) {
            Log.w(TAG, "call $method rejected (signature mismatch?): ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "call $method failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Provider liveness check; also warms the Viber process up. */
    fun ping(ctx: Context): Boolean {
        val env = call(ctx, "ping", null, Bundle()) ?: return false
        return env.optBoolean("ok", false)
    }

    fun chats(ctx: Context, limit: Int = 100): List<Chat> {
        val arr = query(ctx, "$BASE/chats?limit=$limit") ?: return emptyList()
        return elements(arr).map { o ->
            Chat(
                chatId = o.optLong("chat_id"),
                type = o.optInt("type"),
                title = o.optString("title"),
                number = o.optString("number").takeIf { it.isNotEmpty() },
                lastTs = o.optLong("last_ts"),
                lastText = o.optString("last_text").takeIf { it.isNotEmpty() },
                lastDir = o.optString("last_dir").takeIf { it.isNotEmpty() },
                unread = o.optInt("unread"),
                typingTs = o.optLong("typing_ts"),
                typing = o.optInt("typing") == 1,
            )
        }
    }

    /** Messages of a chat, newest first. */
    fun messages(ctx: Context, chatId: Long, limit: Int = 200, beforeId: Long? = null): List<Msg> {
        val sb = StringBuilder("$BASE/messages?chat_id=$chatId&limit=$limit")
        if (beforeId != null) sb.append("&before_id=$beforeId")
        val arr = query(ctx, sb.toString()) ?: return emptyList()
        return elements(arr).map { o ->
            Msg(
                id = o.optLong("id"),
                dir = o.optString("dir"),
                status = o.optInt("status"),
                ts = o.optLong("ts"),
                body = o.optString("body"),
                sender = o.optString("sender").takeIf { it.isNotEmpty() },
                senderNumber = o.optString("sender_number").takeIf { it.isNotEmpty() },
                extraUri = o.optString("extra_uri").takeIf { it.isNotEmpty() },
                quoteId = o.optLong("quote_id"),
                quoteText = o.optString("quote_text").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Last ≤100 incoming events (ring buffer inside the Viber process). */
    fun events(ctx: Context): List<Event> {
        val arr = query(ctx, "$BASE/events") ?: return emptyList()
        return elements(arr).map { o ->
            Event(
                chatId = o.optLong("chat_id"),
                msgId = o.optLong("msg_id"),
                ts = o.optLong("ts"),
                body = o.optString("body"),
                sender = o.optString("sender").takeIf { it.isNotEmpty() },
                number = o.optString("number").takeIf { it.isNotEmpty() },
                quoteId = o.optLong("quote_id"),
                quoteText = o.optString("quote_text").takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Sends `text` into chat `chatId` through Viber's own message engine.
     * Returns true when the provider queued it. The provider may answer
     * "app not initialized yet" right after the Viber process was woken —
     * retried a few times here.
     */
    fun send(ctx: Context, chatId: Long, text: String): Boolean {
        val extras = Bundle().apply { putString("text", text) }
        for (attempt in 1..3) {
            val env = call(ctx, "send", chatId.toString(), extras)
            if (env?.optBoolean("ok", false) == true && env.optBoolean("queued")) {
                Log.i(TAG, "send -> chat $chatId queued (${text.length} chars)")
                return true
            }
            val err = env?.optString("error") ?: "no answer"
            Log.w(TAG, "send attempt $attempt failed: $err")
            if ("not initialized" !in err) break
            try { Thread.sleep(4000L * attempt) } catch (_: InterruptedException) {}
        }
        return false
    }

    /** Resolves the personal chat id for a phone number (e.g. "+37529…"). */
    fun chatIdByNumber(ctx: Context, number: String): Long? =
        chats(ctx).firstOrNull { it.number == number }?.chatId
}
