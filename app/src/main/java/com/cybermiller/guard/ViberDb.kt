package com.cybermiller.guard

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

object ViberDb {
    private const val WAL_MAGIC: Int = 0x377f0682

    data class Result(val db: File, val pages: Int)

    /**
     * Rebuilds a plain SQLite database from `dbFile` + its `-wal` (write-ahead log).
     *
     * Viber keeps its DB open and busy, so on this build the main .db is only a
     * 4 KiB header and every real page lives in the .db-wal. Copying both files
     * races with the writer and can land mid-frame, which breaks SQLite's WAL
     * checksum. Instead we replay the WAL ourselves: every valid frame in order,
     * later frames overwrite earlier ones for the same page. The result is a
     * consistent read-only database snapshot.
     *
     * WAL format (big-endian): 32-byte header, then repeating
     * [24-byte frame header][page data].
     */
    fun rebuild(dbFile: File): Result? {
        val walFile = File(dbFile.path + "-wal")
        if (!dbFile.exists() || !walFile.exists()) return null
        val wal = walFile.readBytes()
        if (wal.size < 32) return null

        val magic = u32be(wal, 0)
        val pageSize = u32be(wal, 8)
        if (magic != WAL_MAGIC) return null
        if (pageSize < 512 || pageSize > 65536) return null

        val frameSize = 24 + pageSize
        var off = 32
        val pages = HashMap<Int, Int>()   // pageNo -> frame offset
        var maxPage = 0
        var frames = 0

        while (off + frameSize <= wal.size) {
            val pgNo = u32be(wal, off)
            if (pgNo == 0 || pgNo > 1_000_000) break
            pages[pgNo] = off
            if (pgNo > maxPage) maxPage = pgNo
            frames++
            off += frameSize
        }
        if (pages.isEmpty()) return null

        val out = File(dbFile.parentFile, "${dbFile.name}.rebuilt")
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(maxPage.toLong() * pageSize)
                val buf = ByteArray(pageSize)
                for ((pgNo, frameOff) in pages) {
                    raf.seek((pgNo - 1).toLong() * pageSize)
                    System.arraycopy(wal, frameOff + 24, buf, 0, pageSize)
                    raf.write(buf)
                }
            }
            Log.i("GuardViberDb", "rebuilt ${dbFile.name}: $frames frames, ${pages.size} pages, maxPage=$maxPage")
            return Result(out, pages.size)
        } catch (t: Throwable) {
            Log.w("GuardViberDb", "rebuild failed: ${t.message}")
            return null
        }
    }

    private fun u32be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}

/**
 * Minimal read-only SQLite pager. Understands table b-tree leaf pages and the
 * record format well enough to read the `messages` / `conversations` tables of
 * Viber's `viber_messages` database.
 *
 * No dependency on the `sqlite3` binary — it is not present on this ROM.
 */
object MiniSqlite {

    private const val TYPE_TEXT = 0   // tag category: blob/text
    private const val TYPE_INT = 1
    private const val TYPE_FLOAT = 2
    private const val TYPE_NULL = 5

    class Db(dbFile: File) : AutoCloseable {
        private val raf = RandomAccessFile(dbFile, "r")
        val pageSize: Int
        val headerOk: Boolean
        companion object {
            /** SQLite page 1 begins with the 100-byte database header; b-tree fields start at offset 100 there. */
            const val PAGE1_HEADER = 100
        }

        init {
            val hdr = ByteArray(100) // header needs >=18 bytes for page size at offset 16-17
            raf.seek(0L)
            raf.readFully(hdr)
            headerOk = String(hdr, 0, 15).startsWith("SQLite format 3")
            val size = u16be(hdr, 16)
            pageSize = if (size == 1) 65536 else size
        }

        /** Returns rows of table `name` as lists of SQLite values (String/Long/Double/null). */
        fun table(name: String, limit: Int = 10_000): List<List<Any?>> {
            val root = rootPage(name) ?: return emptyList()
            val out = ArrayList<List<Any?>>()
            walk(root, out, limit)
            return out
        }

        override fun close() { raf.close() }

        private fun rootPage(name: String): Int? {
            val all = ArrayList<List<Any?>>()
            walk(1, all, Int.MAX_VALUE)
            return all.mapNotNull { r ->
                if (r.size >= 4 && r[0] == "table" && r[1] == name) r[3] as? Int else null
            }.firstOrNull()
        }

        private fun u16be(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        private fun u32be(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        private fun readPage(n: Int): ByteArray {
            val buf = ByteArray(pageSize)
            raf.seek((n - 1).toLong() * pageSize)
            raf.readFully(buf)
            return buf
        }

        private fun walk(pageNo: Int, out: ArrayList<List<Any?>>, limit: Int) {
            if (pageNo < 1 || out.size >= limit) return
            val page = readPage(pageNo)
            val base = if (pageNo == 1) Db.PAGE1_HEADER else 0
            val kind = page[base].toInt() and 0xFF
            if (kind == 0x05) { // interior table b-tree
                val cells = u16be(page, base + 3)
                for (i in 0 until cells) {
                    val child = u32be(page, base + 8 + i * 4)
                    walk(child, out, limit)
                }
                walk(u32be(page, base + 8 + cells * 4), out, limit)
            } else if (kind == 0x0D) { // leaf table b-tree
                val cells = u16be(page, base + 3)
                for (i in 0 until cells) {
                    val ptr = u16be(page, base + 8 + i * 2)
                    val row = cell(page, ptr)
                    if (row != null && out.size < limit) out.add(row)
                }
            }
        }

        private fun cell(page: ByteArray, ptr: Int): List<Any?>? {
            var p = ptr
            val (payloadSize, p2) = readVarint(page, p)   // payload length = record length
            p = p2
            val (rowid, p3) = readVarint(page, p)         // rowid
            p = p3
            val recLen = payloadSize.toInt()
            if (recLen <= 0 || recLen > 1_000_000 || p + recLen > page.size) return null
            val rec = ByteArray(recLen)
            page.copyInto(rec, 0, p, p + recLen)

            // Record: [header_size_varint][serial_type_1]...[serial_type_N][data_1]...
            // header_size_varint counts itself.
            val (headerSize, serialPos0) = readVarint(rec, 0)
            var serialPos = serialPos0.toInt()
            val hdrEnd = (headerSize.coerceAtMost(rec.size.toLong())).toInt()
            var dataPos = hdrEnd

            val out = ArrayList<Any?>(32)
            while (serialPos < hdrEnd && serialPos < rec.size) {
                val t = rec[serialPos].toInt() and 0xFF
                serialPos++
                val serial = serialType(t)
                if (serial.len == 0 && serial.tag == TYPE_NULL) {
                    out.add(null); continue
                }
                if (dataPos + serial.len > rec.size) break
                out.add(valFor(t, rec, dataPos, serial.len))
                dataPos += serial.len
            }
            return out
        }

        private data class Serial(val tag: Int, val len: Int)

        private fun serialType(t: Int): Serial = when {
            t == 0 -> Serial(TYPE_NULL, 0)
            t in 1..6 -> Serial(TYPE_INT, when (t) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 6; else -> 8 })
            t == 7 -> Serial(TYPE_FLOAT, 8)
            t == 8 -> Serial(TYPE_INT, 0)
            t == 9 -> Serial(TYPE_INT, 0)
            t in 10..11 -> Serial(TYPE_NULL, 0)
            else -> Serial(0, if (t % 2 == 0) (t - 12) / 2 else (t - 13) / 2)
        }

        private fun valFor(t: Int, b: ByteArray, off: Int, len: Int): Any? {
            return when {
                t == 8 -> 0L
                t == 9 -> 1L
                t in 10..11 -> null
                t == 7 -> {
                    var bits = 0L
                    for (i in 0 until 8) bits = (bits shl 8) or (b[off + i].toInt() and 0xFF).toLong()
                    java.lang.Double.longBitsToDouble(bits)
                }
                t in 1..6 -> {
                    var v: Long = 0L
                    for (i in 0 until len) v = (v shl 8) or (b[off + i].toInt() and 0xFF).toLong()
                    if (len < 8 && b[off].toInt() < 0) v = v or (0xFFL shl (len * 8))
                    v
                }
                else -> String(b, off, len)
            }
        }

        private fun readVarint(b: ByteArray, off: Int): Pair<Long, Int> {
            var v = 0L
            var p = off
            for (i in 0 until 9) {
                if (p >= b.size) return v to p
                val bytev = b[p].toInt() and 0xFF
                if (i == 8) { v = (v shl 8) or bytev.toLong(); p++; break }
                v = (v shl 7) or (bytev and 0x7F).toLong()
                p++
                if (bytev and 0x80 == 0) break
            }
            return v to p
        }
    }
}