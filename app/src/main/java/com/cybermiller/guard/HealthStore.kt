package com.cybermiller.guard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Historical health samples for the /health graphs. One row per awake hour. */
object HealthStore {
    private const val DB = "health.db"
    private const val VER = 1
    private const val T = "samples"

    /** One measurement. Temperatures are tenths of °C, sizes in MB. */
    data class Sample(
        val ts: Long,
        val battery: Int,        // 0..100
        val charging: Boolean,
        val battTemp: Int,       // °C × 10
        val storageFreeMb: Int,
        val ramFreeMb: Int,
        val load1: Float,        // 1-min load average
        val socTemp: Int,        // °C × 10, 0 = unknown
    )

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, DB, null, VER) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE $T (
                ts INTEGER PRIMARY KEY,
                battery INTEGER, charging INTEGER, batt_temp INTEGER,
                storage_free_mb INTEGER, ram_free_mb INTEGER,
                load1 REAL, soc_temp INTEGER
            )""")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var helper: Db? = null
    private fun h(ctx: Context): Db =
        helper ?: synchronized(this) { helper ?: Db(ctx.applicationContext).also { helper = it } }

    fun insert(ctx: Context, s: Sample) {
        h(ctx).writableDatabase.insertWithOnConflict(T, null, ContentValues().apply {
            put("ts", s.ts)
            put("battery", s.battery)
            put("charging", if (s.charging) 1 else 0)
            put("batt_temp", s.battTemp)
            put("storage_free_mb", s.storageFreeMb)
            put("ram_free_mb", s.ramFreeMb)
            put("load1", s.load1.toDouble())
            put("soc_temp", s.socTemp)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Samples newer than `sinceTs`, oldest first. */
    fun since(ctx: Context, sinceTs: Long): List<Sample> {
        val out = ArrayList<Sample>()
        h(ctx).readableDatabase.query(
            T, null, "ts>=?", arrayOf(sinceTs.toString()), null, null, "ts ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Sample(
                    ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                    battery = c.getInt(c.getColumnIndexOrThrow("battery")),
                    charging = c.getInt(c.getColumnIndexOrThrow("charging")) == 1,
                    battTemp = c.getInt(c.getColumnIndexOrThrow("batt_temp")),
                    storageFreeMb = c.getInt(c.getColumnIndexOrThrow("storage_free_mb")),
                    ramFreeMb = c.getInt(c.getColumnIndexOrThrow("ram_free_mb")),
                    load1 = c.getFloat(c.getColumnIndexOrThrow("load1")),
                    socTemp = c.getInt(c.getColumnIndexOrThrow("soc_temp")),
                ))
            }
        }
        return out
    }

    /** Drops samples older than `beforeTs` — keeps the DB tiny (≤ ~720 rows). */
    fun prune(ctx: Context, beforeTs: Long) {
        h(ctx).writableDatabase.delete(T, "ts<?", arrayOf(beforeTs.toString()))
    }
}
