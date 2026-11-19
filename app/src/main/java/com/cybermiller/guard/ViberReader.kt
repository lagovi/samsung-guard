package com.cybermiller.guard

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Reads Viber conversation history straight out of the Viber app's own SQLite
 * database (`viber_messages` + `.db-wal`), replayed in-process without the
 * `sqlite3` binary (not present on this ROM). Requires root.
 *
 * Read path:
 *   1. Root copies the live db+wal to our own external files dir
 *      (/sdcard/Android/data/com.cybermiller.guard/files/viber/) — retry until
 *      the copied sizes match the live sizes (atomic-ish snapshot).
 *   2. ViberDb.rebuild() replays the WAL into a consistent plain .db.
 *   3. MiniSqlite parses the table b-tree and returns rows.
 *
 * Media: `messages.destination_uri` is a `content://media/external/...` URI
 * which any app can open; fallback root-copies `/sdcard/Pictures/Viber/<FileName>`
 * from `msg_info` JSON.
 */
object ViberReader {
    private const val TAG = "GuardViber"
    private const val SRC_DB = "/data/data/com.viber.voip/databases/viber_messages"
    private const val SRC_WAL = "/data/data/com.viber.voip/databases/viber_messages-wal"

    private var ctxHolder: Context? = null
    private lateinit var destDir: File
    private lateinit var suBin: String

    init {}

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
    )

    /** One conversation row. */
    data class Convo(val id: Long, val name: String, val kind: Int)

    private var initialized = false
    fun init(ctx: Context) {
        if (initialized) return
        ctxHolder = ctx
        destDir = File(ctx.getExternalFilesDir(null), "viber").apply { mkdirs() }
        suBin = findSu()
        initialized = true
        Log.i(TAG, "ViberReader init; root available=${hasRoot()} su=$suBin dest=$destDir")
    }

    fun available(): Boolean = hasRoot() && liveDbSize() > 0

    /** Locates a working `su` binary (Magisk keeps it in /sbin, outside app PATH). */
    private fun findSu(): String {
        for (c in listOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "su")) {
            try {
                val p = ProcessBuilder(c, "-c", "true").redirectErrorStream(true).start()
                if (p.waitFor() == 0) return c
            } catch (_: Throwable) {}
        }
        return "su"
    }

    fun hasRoot(): Boolean {
        return try {
            val p = ProcessBuilder(suBin, "-c", "true").redirectErrorStream(true).start()
            p.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun sh(cmd: String): String {
        return try {
            val p = ProcessBuilder(suBin, "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "su failed: ${t.message}")
            ""
        }
    }

    /** Size of the live Viber db (0 if unreadable). */
    private fun liveDbSize(): Long {
        val out = sh("stat -c %s $SRC_DB 2>/dev/null")
        return out.trim().toLongOrNull() ?: 0
    }

    /**
     * Copies live db+wal to our external files dir, retrying until the copied
     * sizes equal the live sizes (consistent snapshot). Returns the db file.
     */
    private fun snapshot(ctx: Context): File? {
        val db = File(destDir, "snapshot.db")
        val wal = File(destDir, "snapshot.db-wal")
        val liveDb = liveDbSize()
        val liveWal = sh("stat -c %s $SRC_WAL 2>/dev/null").trim().toLongOrNull() ?: 0
        if (liveDb == 0L || liveWal == 0L) return null

        for (i in 1..20) {
            sh("cat $SRC_DB > ${db.path}")
            sh("cat $SRC_WAL > ${wal.path}")
            Thread.sleep(50)
            if (db.length() == liveDb && wal.length() == liveWal) {
                Log.i(TAG, "snapshot ok attempt $i: db=$liveDb wal=$liveWal")
                return db
            }
        }
        Log.w(TAG, "snapshot sizes never matched (db=$liveDb wal=$liveWal)")
        return null
    }

    /** Reads a conversation branch (conversations._id), oldest first, capped. */
    fun readMessages(ctx: Context, conversationId: Long, limit: Int = 200): List<Msg>? {
        val db = snapshot(ctx) ?: return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("messages", 50_000)
            val out = ArrayList<Msg>(rows.size)
            for (r in rows) {
                if (r.size < 24) continue
                if (r[2] as? Long ?: 0L != conversationId) continue     // conversation_id
                if (r[19] as? Long ?: 0L == 1L) continue                // deleted
                out.add(Msg(
                    id = r[0] as? Long ?: 0L,
                    conversationId = r[2] as? Long ?: 0L,
                    incoming = (r[9] as? Long ?: 0L) == 0L,
                    ts = r[6] as? Long ?: 0L,
                    body = r[10] as? String ?: "",
                    mime = (r[23] as? Long ?: 0L).toInt(),
                    mediaUri = r[22] as? String,
                    mediaFileName = null,
                ))
                if (out.size >= limit) break
            }
            Log.i(TAG, "readMessages conv=$conversationId -> ${out.size} msgs")
            return out
        }
    }

    /** All visible conversations (1:1 + groups). */
    fun readConversations(ctx: Context, limit: Int = 100): List<Convo>? {
        val db = snapshot(ctx) ?: return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("conversations", 50_000)
            val out = ArrayList<Convo>(rows.size)
            for (r in rows) {
                if (r.size < 12) continue
                if (r[10] as? Long ?: 0L == 1L) continue                // deleted
                out.add(Convo(
                    id = r[0] as? Long ?: 0L,
                    name = r[11] as? String ?: "",
                    kind = (r[1] as? Long ?: 0L).toInt(),
                ))
                if (out.size >= limit) break
            }
            Log.i(TAG, "readConversations -> ${out.size}")
            return out
        }
    }

    /** Resolve participant phone number from participants_info._id. */
    fun participantNumber(ctx: Context, participantInfoId: Long): String? {
        val db = snapshot(ctx) ?: return null
        val rebuilt = ViberDb.rebuild(db) ?: return null
        MiniSqlite.Db(rebuilt.db).use { sqlite ->
            val rows = sqlite.table("participants_info", 10_000)
            for (r in rows) {
                if (r.size > 3 && r[0] == participantInfoId.toLong()) {
                    return r[3] as? String
                }
            }
        }
        return null
    }

    /** Copy attached media to our cache dir and return local File. */
    fun exportMedia(ctx: Context, mediaUri: String?, fileName: String?): File? {
        val dir = File(ctx.cacheDir, "viber_media").apply { mkdirs() }
        val out = File(dir, "m_${System.currentTimeMillis()}_${fileName?.let { it.replace("[^a-zA-Z0-9.]".toRegex(), "_") } ?: "bin"}")

        // 1) MediaStore destination_uri — directly openable by our app
        mediaUri?.let { uri ->
            try {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() > 0) {
                    Log.i(TAG, "media from content uri $uri")
                    return out
                } else {
                    out.delete()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "content uri read failed: ${t.message}")
            }
        }

        // 2) root-copy from /sdcard/Pictures/Viber/<fileName>
        fileName?.let { name ->
            val src = "/sdcard/Pictures/Viber/$name"
            sh("test -f $src && cat $src > ${out.path}")
            if (out.length() > 0) {
                Log.i(TAG, "media copied from $src")
                return out
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