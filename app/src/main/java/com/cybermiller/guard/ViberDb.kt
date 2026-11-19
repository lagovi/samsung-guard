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

    class Db(dbFile: File) : AutoCloseable {
        private val raf = RandomAccessFile(dbFile, "r")
        val pageSize: Int
        val headerOk: Boolean
        val pageCount: Int
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
            pageCount = (raf.length() / pageSize).toInt()
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
            val found = all.mapNotNull { r ->
                if (r.size >= 4 && r[0] == "table" && r[1] == name) (r[3] as? Long)?.toInt() else null
            }.firstOrNull()
            Log.i("GuardViberDb", "rootPage($name) -> $found (scanned ${all.size} sqlite_master rows)")
            return found
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
            if (pageNo < 1 || pageNo > pageCount) return
            val base = if (pageNo == 1) Db.PAGE1_HEADER else 0
            val kind = page[base].toInt() and 0xFF
            if (kind == 0x05) { // interior table b-tree
                val cells = u16be(page, base + 3)
                for (i in 0 until cells) {
                    val cellPtrOff = base + 8 + i * 2
                    val cellContentOff = u16be(page, cellPtrOff)
                    val child = u32be(page, cellContentOff)
                    if (child >= 1 && child <= pageCount) walk(child, out, limit)
                }
                val right = u32be(page, base + 8 + cells * 2)
                if (right >= 1 && right <= pageCount) walk(right, out, limit)
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
            val payloadSize = readVarint(page, p).first   // payload length = record length
            p = readVarint(page, p).second
            val rowid = readVarint(page, p).first         // rowid (= _id for INTEGER PRIMARY KEY)
            p = readVarint(page, p).second
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

            val out = ArrayList<Any?>(33)
            out.add(rowid) // _id = rowid (INTEGER PRIMARY KEY is not stored in record body)
            while (serialPos < hdrEnd && serialPos < rec.size) {
                val t = rec[serialPos].toInt() and 0xFF
                serialPos++
                // NULL serial types: 0, 10, 11 (reserved)
                if (t == 0 || t == 10 || t == 11) {
                    out.add(null); continue
                }
                // Constant integers: 8 → 0, 9 → 1 (zero data bytes)
                if (t == 8) { out.add(0L); continue }
                if (t == 9) { out.add(1L); continue }
                val len = serialLen(t)
                if (dataPos + len > rec.size) break
                out.add(valFor(t, rec, dataPos, len))
                dataPos += len
            }
            return out
        }

        /** Returns data length (bytes) for a SQLite serial type, or -1 if the type carries no data. */
        private fun serialLen(t: Int): Int = when {
            t in 1..6 -> when (t) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 6; else -> 8 }
            t == 7 -> 8
            t >= 12 -> if (t % 2 == 0) (t - 12) / 2 else (t - 13) / 2
            else -> -1
        }

        private fun valFor(t: Int, b: ByteArray, off: Int, len: Int): Any? {
            return when {
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
                else -> String(b, off, len)   // BLOB / TEXT (12+)
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