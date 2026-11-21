package com.cybermiller.guard

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Reads Viber conversation data through [ViberApi] (the custom build's internal
 * provider) and exports media attachments to local files for Telegram upload.
 *
 * Media access paths for a message:
 *  1. `extra_uri` often embeds a public MediaStore URI inside Viber's internal
 *     provider URI (base64 in the last path segment) — decoded and opened with
 *     READ_EXTERNAL_STORAGE.
 *  2. Plain `content://media/...` in extra_uri — opened directly.
 *  3. Saved copies under /sdcard/Pictures/Viber as a last resort.
 */
object ViberReader {
    private const val TAG = "GuardViber"

    /** Copy attached media to our cache dir and return local File, or null. */
    fun exportMedia(ctx: Context, msg: ViberApi.Msg): File? {
        val dir = File(ctx.cacheDir, "viber_media").apply { mkdirs() }
        val out = File(dir, "m_${msg.id}.jpg")

        for (uri in candidateUris(msg.extraUri)) {
            try {
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() > 0) {
                    Log.i(TAG, "media #${msg.id} from $uri (${out.length()} bytes)")
                    return out
                }
                out.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "media read failed for $uri: ${t.message}")
                out.delete()
            }
        }
        Log.w(TAG, "media unavailable for #${msg.id} (extra_uri=$msg.extraUri)")
        return null
    }

    /** MediaStore URIs to try, in order: decoded-from-internal first, then raw. */
    private fun candidateUris(extraUri: String?): List<String> =
        listOfNotNull(decodeEmbeddedUri(extraUri), extraUri).filter { it.startsWith("content://") }

    /**
     * Viber stores the public MediaStore URI base64-encoded inside its internal
     * provider URI:
     *   content://com.viber.voip.provider.internal_files/message/image/<b64>?duplicate=true
     */
    private fun decodeEmbeddedUri(uri: String?): String? {
        if (uri == null || !uri.contains("provider.internal_files")) return null
        val token = uri.substringAfterLast('/').substringBefore('?').replace("%3D", "=")
        return try {
            val decoded = String(android.util.Base64.decode(token, android.util.Base64.DEFAULT))
            decoded.takeIf { it.startsWith("content://") }
        } catch (_: Exception) {
            null
        }
    }
}

/** Sends (or opens) Viber chats. */
object ViberSender {
    private const val TAG = "GuardViber"

    /** Opens the 1:1 chat with `number` in Viber (deep link). */
    fun openChat(ctx: Context, number: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
            Uri.parse("viber://chat?number=$number"))
            .setPackage("com.viber.voip")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "deep link failed: ${t.message}")
        }
    }

    /**
     * Sends `text` into the Viber chat with `number` through the LiteAPI
     * (Viber's own message engine — Unicode, newlines, everything just works).
     */
    fun sendReply(ctx: Context, number: String, text: String): Boolean {
        val chatId = ViberApi.chatIdByNumber(ctx, number)
        if (chatId == null) {
            Log.w(TAG, "no chat found for $number")
            return false
        }
        return ViberApi.send(ctx, chatId, text)
    }
}
