package com.cybermiller.guard

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Reads Viber conversation history out of Viber's own SQLite database.
 *
 * Arch: a Magisk `service.d` script (runs as root) copies
 *   /data/data/com.viber.voip/databases/viber_messages + .db-wal
 *   -> /sdcard/Android/data/com.cybermiller.guard/files/viber/snapshot.db(+wal)
 *   every ~60 s.
 * This app then reads that copy — no `su` needed from the app process (SELinux
 * on this ROM blocks `su` from untrusted_app uid anyway).
 *
 * The copied pair is replayed with [ViberDb.rebuild] (WAL -> plain db) and
 * parsed with [MiniSqlite] (no `sqlite3` binary on this ROM).
 *
 * Setup (one time, as root shell):
 *   echo '#!/system/bin/sh
 *   while true; do
 *     cat /data/data/com.viber.voip/databases/viber_messages \
 *       > /sdcard/Android/data/com.cybermiller.guard/files/viber/snapshot.db
 *     cat /data/data/com.viber.voip/databases/viber_messages-wal \
 *       > /sdcard/Android/data/com.cybermiller.guard/files/viber/snapshot.db-wal
 *     chmod 666 /sdcard/Android/data/com.cybermiller.guard/files/viber/snapshot.db*
 *     sleep 60
 *   done' > /data/adb/service.d/99-viber-guard.sh
 *   chmod 755 /data/adb/service.d/99-viber-guard.sh
 *
 * Then reboot (Magisk runs service.d at boot) or run the script manually.
 */
object ViberReader {
    private const val TAG = "GuardViber"

    /** One message row. */
    data class Msg(
        val id: Long,
        val conversationId: Long,
        val incoming: Boolean,   // send_type == 0
        val ts: Long,            // msg_date ms
        val body: String,        // text or media caption
        val mime: Int,           // extra_mime (0=text, 1=photo, …)
        val mediaUri: String?,   // destination_uri for MediaStore or extra_uri
        val mediaFileName: String?,
        /** participants_info._id of the sender (resolves to a phone number). */
        val participantId: Long = 0L,
    )

    /** One conversation row. */
    data class Convo(val id: Long, val name: String, val kind: Int)

    private var ctxHolder: Context? = null
    private lateinit var destDir: File
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        ctxHolder = ctx
        destDir = File(ctx.getExternalFilesDir(null), "viber").apply { mkdirs() }
        initialized = true
        Log.i(TAG, "ViberReader init; dest=$destDir snapshotReady=${snapshotReady()}")
    }

    /** True when a fresh root-copied snapshot exists (<= 2 min old). */
    fun snapshotReady(): Boolean {
        val db = File(destDir, "snapshot.db")
        return db.exists() && db.length() > 2048 &&
            System.currentTimeMillis() - db.lastModified() < 2 * 60 * 1000
    }

    fun available(): Boolean = snapshotReady()

    private fun dbFile() = File(destDir, "snapshot.db")
    private fun walFile() = File(destDir, "snapshot.db-wal")

    /** Reads a conversation branch (conversations._id), oldest first, capped. */
    fun readMessages(ctx: Context, conversationId: Long, limit: Int = 200): List<Msg>? {
        val db = dbFile(); val wal = walFile()
        if (!db.exists() || db.length() < 2048) return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("messages", 50_000)
            val out = ArrayList<Msg>(rows.size)
            for (r in rows) {
                if (out.isEmpty()) Log.i(TAG, "MSGROW[${r.size}]=" + r.map { it?.toString() ?: "null" })
                if (r.size < 25) continue                               // need msg_date(6), send_type(9), body(10), extra_mime(23)
                if (r[2] as? Long ?: 0L != conversationId) continue     // conversation_id
                if (r[19] as? Long ?: 0L == 1L) continue                // deleted
                out.add(Msg(
                    id = r[0] as? Long ?: 0L,
                    conversationId = r[2] as? Long ?: 0L,
                    incoming = (r[9] as? Long ?: 0L) == 0L,
                    ts = r[6] as? Long ?: 0L,
                    body = r.getOrNull(10) as? String ?: "",
                    mime = (r.getOrNull(23) as? Long ?: 0L).toInt(),
                    mediaUri = r.getOrNull(22) as? String ?: r.getOrNull(21) as? String,
                    mediaFileName = null,
                    participantId = r.getOrNull(4) as? Long ?: 0L,
                ))
                if (out.size >= limit) break
            }
            Log.i(TAG, "readMessages conv=$conversationId -> ${out.size} msgs")
            return out
        }
    }

    /** All visible conversations (1:1 + groups). */
    fun readConversations(ctx: Context, limit: Int = 100): List<Convo>? {
        val db = dbFile()
        if (!db.exists() || db.length() < 2048) return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("conversations", 50_000)
            Log.i(TAG, "readConversations: walked ${rows.size} rows from sqlite_master")
            val out = ArrayList<Convo>(rows.size)
            for (r in rows) {
                if (r.isEmpty()) continue                               // need at least _id
                if (r.size > 10 && r[10] as? Long ?: 0L == 1L) continue // deleted
                out.add(Convo(
                    id = r[0] as? Long ?: 0L,
                    name = r.getOrNull(11) as? String ?: "",
                    kind = (r.getOrNull(1) as? Long ?: 0L).toInt(),
                ))
                if (out.size >= limit) break
            }
            Log.i(TAG, "readConversations -> ${out.size}")
            return out
        }
    }

    /** Resolve participant phone number from participants_info._id. */
    fun participantNumber(ctx: Context, participantInfoId: Long): String? {
        val db = dbFile()
        if (!db.exists()) return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("participants_info", 10_000)
            for (r in rows) {
                if (r.size > 3 && r[0] == participantInfoId.toLong()) {
                    return r.getOrNull(3) as? String
                }
            }
        }
        return null
    }

    /** Copy attached media to our cache dir and return local File. */
    fun exportMedia(ctx: Context, mediaUri: String?, fileName: String?): File? {
        val dir = File(ctx.cacheDir, "viber_media").apply { mkdirs() }
        val out = File(dir, "m_${System.currentTimeMillis()}_${fileName?.let {
            it.replace("[^a-zA-Z0-9.]".toRegex(), "_") } ?: "bin"}")

        // 1) MediaStore destination_uri — directly openable by our app
        mediaUri?.let { uri ->
            try {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() > 0) {
                    Log.i(TAG, "media from content uri $uri")
                    return out
                } else { out.delete() }
            } catch (t: Throwable) {
                Log.w(TAG, "content uri read failed: ${t.message}")
            }
        }

        // 2) direct file read from /sdcard/Pictures/Viber/<fileName> (may fail perms)
        fileName?.let { name ->
            val src = "/sdcard/Pictures/Viber/$name"
            val f = File(src)
            if (f.exists() && f.canRead()) {
                f.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() > 0) {
                    Log.i(TAG, "media copied from $src")
                    return out
                } else { out.delete() }
            }
        }
        out.delete()
        return null
    }
}

/** Sends (or opens for sending) messages into Viber. */
object ViberSender {
    private const val TAG = "GuardViber"

    /** Opens the 1:1 chat with `number` in Viber (deep link). */
    fun openChat(ctx: Context, number: String) {
        val uri = android.net.Uri.parse("viber://chat?number=$number")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .setPackage("com.viber.voip")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "deep link failed: ${t.message}")
        }
    }
}