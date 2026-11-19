package com.cybermiller.guard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsStore {
    private const val DB = "guard.db"
    private const val VER = 1
    private const val T_SMS = "sms"

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, DB, null, VER) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE $T_SMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT, body TEXT, ts INTEGER,
                verdict TEXT, llm_reply TEXT, handled INTEGER DEFAULT 0
            )""")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var helper: Db? = null
    private fun h(ctx: Context): Db =
        helper ?: synchronized(this) { helper ?: Db(ctx.applicationContext).also { helper = it } }

    fun onSmsReceived(ctx: Context, address: String, body: String, timestamp: Long) {
        val db = h(ctx).writableDatabase
        val cv = ContentValues().apply {
            put("address", address)
            put("body", body)
            put("ts", timestamp)
        }
        val rowId = db.insert(T_SMS, null, cv)
        // Durable handoff: JobScheduler outlives the 30 s goAsync() window
        // that SmsReceiver has. Heavy LLM call happens inside the job.
        Pipeline.onNewSms(ctx, rowId, address, body, timestamp)
    }

    fun get(ctx: Context, id: Long): Row? {
        h(ctx).readableDatabase.query(
            T_SMS, null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Row(
                address = c.getString(c.getColumnIndexOrThrow("address")),
                body = c.getString(c.getColumnIndexOrThrow("body")),
            )
        }
    }

    data class Row(val address: String, val body: String)

    fun markHandled(ctx: Context, id: Long, verdict: String, reply: String) {
        val db = h(ctx).writableDatabase
        db.update(T_SMS, ContentValues().apply {
            put("verdict", verdict)
            put("llm_reply", reply)
            put("handled", 1)
        }, "id=?", arrayOf(id.toString()))
    }

    fun deleteSms(ctx: Context, id: Long) {
        h(ctx).writableDatabase.delete(T_SMS, "id=?", arrayOf(id.toString()))
    }
}