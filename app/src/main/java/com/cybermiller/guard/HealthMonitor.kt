package com.cybermiller.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Device health: hourly sampling while the device is awake, stored history,
 * graphs on demand (Telegram menu → /health).
 *
 * Nothing is pushed on a schedule — the report is sent only when the owner
 * asks for it. Sampling: a plain Thread.sleep loop; when the CPU suspends the
 * timer freezes, so sleep periods produce no samples by themselves — plus an
 * explicit isInteractive check (screen off = asleep).
 */
object HealthMonitor {
    private const val TAG = "GuardHealth"
    // The sample is cheap (a few /proc and sysfs reads, one StatFs, one sticky
    // broadcast; no wake-ups — the loop only runs while the device is awake).
    private const val SAMPLE_INTERVAL_MS = 10_000L
    private const val RETENTION_MS = 14L * 24 * 3600 * 1000   // 14 days (matches the graph)
    private const val GRAPH_DAYS = 14L
    private const val SLEEP_GAP_MS = 60_000L                  // no samples this long = asleep

    @Volatile private var running = false

    // Previous /proc/stat counters for the CPU-load delta. Volatile pair kept
    // consistent under a lock (the collector thread is the only writer).
    private var prevCpuTotal: Long = 0L
    private var prevCpuIdle: Long = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        Log.i(TAG, "health monitor start (10 s, awake only)")
        val appCtx = ctx.applicationContext
        Thread {
            try {
                collectIfAwake(appCtx)   // one sample right after service start
                while (running) {
                    try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) {}
                    if (!running) break
                    try { collectIfAwake(appCtx) } catch (t: Throwable) {
                        Log.w(TAG, "collect failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "health monitor crashed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    /** Takes a sample only when the device is awake (screen on = "не спит"). */
    private fun collectIfAwake(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            Log.i(TAG, "device asleep — sample skipped")
            return
        }
        val s = sample(ctx)
        HealthStore.insert(ctx, s)
        HealthStore.prune(ctx, System.currentTimeMillis() - RETENTION_MS)
        Log.i(TAG, "sample: bat=${s.battery}% temp=${s.battTemp / 10.0}°C " +
            "sto=${s.storageFreeMb / 1024}GB ram=${s.ramFreeMb}MB load=${s.load1} " +
            "cpu=${s.cpuLoadPct}% freq=${s.cpuFreqAvgMhz}MHz")
    }

    fun sample(ctx: Context): HealthStore.Sample {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val b = ctx.registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED))
        val battTemp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val storageFreeMb = try {
            (StatFs("/data").availableBytes / (1024 * 1024)).toInt()
        } catch (_: Exception) { 0 }

        val ramFreeMb = try {
            val mi = File("/proc/meminfo").readLines()
            fun kb(key: String) = mi.firstOrNull { it.startsWith(key + ":") }
                ?.substringAfter(":")?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: 0L
            var avail = kb("MemAvailable")
            if (avail <= 0L) avail = kb("MemFree") + kb("Buffers") + kb("Cached")
            (avail / 1024L).toInt()
        } catch (_: Exception) { 0 }

        val load1 = try {
            File("/proc/loadavg").readText().trim().split(" ").first().toFloatOrNull() ?: 0f
        } catch (_: Exception) { 0f }

        // CPU die temperature: the tsens_tz_sensor* zones (0..4 here) report
        // whole °C on this device, other zone types use millidegrees — detect
        // the scale and take the hottest of the five on-die sensors.
        val socTemp = try {
            var best = 0
            for (i in 0..4) {
                val t = File("/sys/class/thermal/thermal_zone$i/temp").readText().trim().toIntOrNull() ?: continue
                val c = when {
                    t > 10000 -> t / 1000   // millidegrees
                    t > 200   -> t / 10     // tenths
                    else      -> t          // whole degrees (tsens)
                }
                if (c > best) best = c
            }
            best * 10                       // stored as tenths of °C
        } catch (_: Exception) { 0 }

        val cpuLoadPct = readCpuLoadPct()
        val freqs = readCpuFreqsMhz()
        val cpuFreqAvg = if (freqs.isEmpty()) 0 else freqs.sum() / freqs.size

        return HealthStore.Sample(
            ts = System.currentTimeMillis(),
            battery = level,
            charging = charging,
            battTemp = battTemp,        // EXTRA_TEMPERATURE is already tenths of °C
            storageFreeMb = storageFreeMb,
            ramFreeMb = ramFreeMb,
            load1 = load1,
            socTemp = socTemp,
            cpuLoadPct = cpuLoadPct,
            cpuFreqAvgMhz = cpuFreqAvg,
        )
    }

    /**
     * CPU utilization as a percentage, computed from /proc/stat deltas between
     * consecutive samples. Returns -1 for the very first sample (no baseline).
     */
    private fun readCpuLoadPct(): Int {
        return try {
            val line = File("/proc/stat").readText().trim().split("\n").first()
            val t = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (t.size < 4) return -1
            val total = t.sum()
            val idle = t[3] + (t.getOrNull(4) ?: 0L)    // idle + iowait
            synchronized(this) {
                val pct = if (prevCpuTotal > 0L) {
                    val dTotal = total - prevCpuTotal
                    val dIdle = idle - prevCpuIdle
                    if (dTotal > 0L) (100f * (dTotal - dIdle) / dTotal + 0.5f).toInt() else -1
                } else -1
                prevCpuTotal = total
                prevCpuIdle = idle
                pct
            }
        } catch (_: Exception) { -1 }
    }

    /** Current frequency of every online core, MHz (empty list if unreadable). */
    private fun readCpuFreqsMhz(): List<Int> {
        val out = ArrayList<Int>(8)
        for (i in 0..7) {
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (!f.exists()) break
                val khz = f.readText().trim().toLongOrNull() ?: continue
                out.add((khz / 1000L).toInt())
            } catch (_: Exception) { break }
        }
        return out
    }

    /** Human-readable current status (the /health text part). */
    fun currentText(ctx: Context): String {
        val s = sample(ctx)
        val sb = StringBuilder()
        sb.append("🔋 Батарея: ${s.battery}%")
        if (s.battTemp > 0) sb.append(", ${"%.1f".format(s.battTemp / 10.0)}°C")
        sb.append(if (s.charging) " (заряжается)\n" else "\n")
        try {
            val sf = StatFs("/data")
            val freeGb = sf.availableBytes / 1e9
            val totalGb = sf.totalBytes / 1e9
            sb.append("💾 Хранилище: ${"%.1f".format(freeGb)}/${"%.1f".format(totalGb)} ГБ свободно\n")
        } catch (_: Exception) {}
        try {
            val mi = File("/proc/meminfo").readLines()
            fun kb(key: String) = mi.firstOrNull { it.startsWith(key + ":") }
                ?.substringAfter(":")?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: 0L
            var avail = kb("MemAvailable")
            if (avail <= 0L) avail = kb("MemFree") + kb("Buffers") + kb("Cached")
            sb.append("🧠 RAM: ${avail / 1024}/${kb("MemTotal") / 1024} МБ свободно\n")
        } catch (_: Exception) {}
        try {
            val loads = File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString(" ")
            sb.append("⚙️ Load: $loads\n")
        } catch (_: Exception) {}
        if (s.cpuLoadPct >= 0) sb.append("🧮 CPU: ${s.cpuLoadPct}%\n")
        val freqs = readCpuFreqsMhz()
        if (freqs.isNotEmpty()) {
            sb.append("📡 Ядра (${freqs.size}): " +
                freqs.joinToString("/") { "%.1f".format(it / 1000f) } + " ГГц\n")
        }
        if (s.socTemp > 0) sb.append("🌡 Процессор: ${"%.1f".format(s.socTemp / 10.0)}°C\n")
        return sb.toString().trimEnd()
    }

    /** Full /health reply: current status text + history graph photo. */
    fun sendReport(ctx: Context) {
        TelegramClient.sendText("<b>📊 Текущее состояние</b>\n" + currentText(ctx))
        val since = System.currentTimeMillis() - GRAPH_DAYS * 24 * 3600 * 1000
        val samples = HealthStore.since(ctx, since)
        if (samples.size < 3) {
            TelegramClient.sendText(
                "Графики появятся, когда накопятся замеры (раз в 10 с, пока телефон не спит). " +
                    "Сейчас точек: ${samples.size}.")
            return
        }
        val png = drawGraphs(ctx, samples)
        if (png != null) TelegramClient.sendPhoto(png,
            "📊 Здоровье за $GRAPH_DAYS дней · замер раз в 10 с, пока телефон не спит · серые полосы — сон")
        else TelegramClient.sendText("❌ Не удалось построить графики")
    }

    // ---------------- graphs ----------------

    private class Panel(
        val title: String, val unit: String, val color: Int,
        /** Index-aligned with the drawn sample list; NaN = no reading. */
        val values: List<Float>,
        /** Fixed top of the scale (e.g. 100% for battery); null = max*1.08. */
        val fixedMax: Float? = null,
    )

    /** Renders the metric panels into one PNG. Sleep periods (no samples for
     *  >60 s — CPU was suspended) are shaded, the time line itself is continuous. */
    private fun drawGraphs(ctx: Context, samplesIn: List<HealthStore.Sample>): File? {
        return try {
            // Sleep bands come from gaps in the FULL list (before downsampling
            // hides them), then points are thinned for drawing.
            val bands = ArrayList<Pair<Long, Long>>()
            for (i in 1 until samplesIn.size) {
                val gap = samplesIn[i].ts - samplesIn[i - 1].ts
                if (gap > SLEEP_GAP_MS) bands.add(samplesIn[i - 1].ts to samplesIn[i].ts)
            }
            val samples = if (samplesIn.size <= 1200) samplesIn
            else {
                val step = (samplesIn.size + 1199) / 1200
                (0 until samplesIn.size step step).map { samplesIn[it] } + samplesIn.last()
            }

            val t0 = samples.first().ts
            val t1 = samples.last().ts
            val span = (t1 - t0).coerceAtLeast(1L)

            // CPU panels keep values aligned with `samples`: NaN marks a point
            // that has no reading (the first tick after start has no previous
            // /proc/stat sample to diff against). A filtered list here would
            // desynchronise value indices from sample timestamps and crash
            // the polyline loop with IndexOutOfBoundsException.
            val hasCpuLoad = samples.any { it.cpuLoadPct >= 0 }
            val hasCpuFreq = samples.any { it.cpuFreqAvgMhz > 0 }
            val panels = buildList {
                add(Panel("🔋 Батарея, %", "%", Color.rgb(76, 217, 100),
                    samples.map { it.battery.toFloat() }, fixedMax = 100f))
                add(Panel("🌡 Температура батареи, °C", "°C", Color.rgb(255, 149, 0),
                    samples.map { it.battTemp / 10f }))
                if (samples.any { it.socTemp > 0 }) add(Panel("🌡 Температура CPU, °C", "°C",
                    Color.rgb(255, 87, 34),
                    samples.map { if (it.socTemp > 0) it.socTemp / 10f else Float.NaN }))
                add(Panel("💾 Свободно /data, ГБ", " ГБ", Color.rgb(90, 200, 250),
                    samples.map { it.storageFreeMb / 1024f }))
                add(Panel("🧠 Свободно RAM, МБ", " МБ", Color.rgb(175, 122, 255),
                    samples.map { it.ramFreeMb.toFloat() }))
                if (hasCpuLoad) add(Panel("🧮 Загрузка CPU, %", "%",
                    Color.rgb(255, 76, 76),
                    samples.map { if (it.cpuLoadPct >= 0) it.cpuLoadPct.toFloat() else Float.NaN },
                    fixedMax = 100f))
                if (hasCpuFreq) add(Panel("📡 Частота CPU (средн.), МГц", " МГц",
                    Color.rgb(255, 204, 92),
                    samples.map { if (it.cpuFreqAvgMhz > 0) it.cpuFreqAvgMhz.toFloat() else Float.NaN }))
            }

            val w = 1000
            // with 7+ panels each shrinks a bit so the whole picture still
            // fits a phone screen
            val panelH = (1500f / panels.size).toInt().coerceIn(190, 245)
            val padL = 20f
            val padR = 150f
            val top = 16f
            val h = (panels.size * panelH + 76).toInt()   // +76 = time-axis row
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(22, 24, 30))

            val bgPaint = Paint().apply { color = Color.rgb(30, 33, 41) }
            val bandPaint = Paint().apply { color = Color.rgb(48, 54, 66) }   // sleep bands
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 30f; isFakeBoldText = true
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 166, 178); textSize = 22f
            }
            val gridPaint = Paint().apply {
                color = Color.rgb(70, 76, 88); strokeWidth = 1f
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 3f; style = Paint.Style.STROKE
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val platePaint = Paint().apply { color = Color.argb(165, 22, 24, 30) }

            // Value label on a semi-transparent plate, so the text stays
            // readable where it lands on the plotted line (CPU frequency
            // comb, spikes — the digits just disappear into the line).
            fun label(text: String, tx: Float, ty: Float) {
                val fm = labelPaint.fontMetrics
                canvas.drawRoundRect(
                    RectF(tx - 5f, ty + fm.ascent - 4f, tx + labelPaint.measureText(text) + 5f, ty + fm.descent + 4f),
                    6f, 6f, platePaint)
                canvas.drawText(text, tx, ty, labelPaint)
            }

            val dayFmt = SimpleDateFormat("dd.MM", Locale.getDefault())

            // Shared time axis: adaptive ticks drawn as grid lines on every
            // panel, with a single label row under the bottom panel.
            val hourMs = 3600_000L
            val dayMs = 24 * hourMs
            val stepMs = longArrayOf(
                5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L,
                hourMs, 2 * hourMs, 3 * hourMs, 6 * hourMs, 12 * hourMs,
                dayMs, 2 * dayMs, 7 * dayMs, 14 * dayMs,
            ).first { span / it <= 12 }
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val ticks = ArrayList<Pair<Long, String>>()
            val tc = Calendar.getInstance()
            tc.timeInMillis = t0
            if (stepMs < dayMs) {
                tc.set(Calendar.MINUTE, 0); tc.set(Calendar.SECOND, 0); tc.set(Calendar.MILLISECOND, 0)
            } else {
                tc.set(Calendar.HOUR_OF_DAY, 0); tc.set(Calendar.MINUTE, 0)
                tc.set(Calendar.SECOND, 0); tc.set(Calendar.MILLISECOND, 0)
            }
            if (tc.timeInMillis < t0) tc.add(Calendar.MILLISECOND, stepMs.toInt())
            while (tc.timeInMillis <= t1) {
                val ts = tc.timeInMillis
                val midnight = tc.get(Calendar.HOUR_OF_DAY) == 0
                ticks.add(ts to when {
                    stepMs >= dayMs || midnight -> dayFmt.format(Date(ts))
                    else -> timeFmt.format(Date(ts))
                })
                tc.add(Calendar.MILLISECOND, stepMs.toInt())
            }

            panels.forEachIndexed { pi, p ->
                val y0 = top + pi * panelH
                val plotL = padL
                val plotR = w - padR
                val plotT = y0 + 44f
                val plotB = y0 + panelH - 34f

                canvas.drawRect(plotL, y0 + 6f, w - 10f, y0 + panelH - 6f, bgPaint)
                canvas.drawText(p.title, padL + 10f, y0 + 34f, titlePaint)

                val maxV = p.values.filter { !it.isNaN() }.maxOrNull() ?: 0f
                val lo = 0f                                  // always start from zero
                val hi = p.fixedMax ?: (maxV * 1.08f).coerceAtLeast(1f)
                val range = (hi - lo).coerceAtLeast(1e-6f)

                fun x(ts: Long) = plotL + (ts - t0).toFloat() / span * (plotR - plotL)
                fun y(v: Float) = plotB - (v - lo) / range * (plotB - plotT)

                // shared time axis grid (labels are drawn once at the bottom)
                for ((ts, _) in ticks) {
                    val tx = x(ts)
                    if (tx >= plotL && tx <= plotR) canvas.drawLine(tx, plotT, tx, plotB, gridPaint)
                }

                // sleep bands: vertical stripes where the device was suspended
                for ((b0, b1) in bands) {
                    val bx0 = x(b0); val bx1 = x(b1)
                    if (bx1 > plotL && bx0 < plotR) canvas.drawRect(bx0, plotT, bx1, plotB, bandPaint)
                }

                // "0" anchor (lo is always zero), centred on its level
                canvas.drawText(fmt(p, 0f), plotR + 8f, plotB + 8f, labelPaint)

                // polyline — continuous (sleep is shown by the shaded bands);
                // NaN points (no reading) break the line
                linePaint.color = p.color
                dotPaint.color = p.color
                val path = Path()
                var penDown = false
                samples.forEachIndexed { i, s ->
                    val v = p.values[i]
                    if (v.isNaN()) { penDown = false; return@forEachIndexed }
                    val px = x(s.ts); val py = y(v)
                    if (penDown) path.lineTo(px, py) else { path.moveTo(px, py); penDown = true }
                }
                canvas.drawPath(path, linePaint)

                // min / max point markers; a marker coinciding with the newest
                // point is skipped — the last-value label already covers it
                val lastIdx = samples.size - 1
                val valid = p.values.withIndex().filter { !it.value.isNaN() }
                if (valid.map { it.value }.distinct().size > 1) {
                    val maxIdx = valid.maxByOrNull { it.value }!!.index
                    val minIdx = valid.minByOrNull { it.value }!!.index
                    for ((idx, isMax) in listOf(maxIdx to true, minIdx to false)) {
                        if (idx < 0 || idx == lastIdx) continue
                        if (!isMax && idx == maxIdx) continue   // flat range, one marker is enough
                        val px = x(samples[idx].ts); val py = y(p.values[idx])
                        canvas.drawCircle(px, py, 4f, dotPaint)
                        val tx = if (px > plotR - 120f) px - 84f else px + 10f
                        val ty = when {
                            // near the top edge the text would sit on the title — put it below
                            isMax && py < plotT + 30f -> py + 32f
                            isMax -> py - 12f
                            py > plotB - 26f -> py - 12f
                            else -> py + 26f
                        }
                        label(fmt(p, p.values[idx]), tx, ty)
                    }
                }

                // last value dot + label
                val last = samples.last()
                val lastV = p.values.last()
                if (!lastV.isNaN()) {
                    val lx = x(last.ts); val ly = y(lastV)
                    canvas.drawCircle(lx, ly, 5f, dotPaint)
                    label(fmt(p, lastV), lx - 70f, ly - 12f)
                }
            }

            // one label row for the shared time axis
            for ((ts, lbl) in ticks) {
                val tx = padL + (ts - t0).toFloat() / span * (w - padR - padL)
                if (tx > padL + 6f && tx < w - padR - 6f) {
                    canvas.drawText(lbl, tx - labelPaint.measureText(lbl) / 2f, h - 12f, labelPaint)
                }
            }

            val dir = File(ctx.cacheDir, "health").apply { mkdirs() }
            val out = File(dir, "health_graph.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "drawGraphs failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun fmt(p: Panel, v: Float): String =
        // The unit must NOT go into the format string — a trailing "%" (battery
        // panel) makes Formatter throw UnknownFormatConversionException.
        (if (kotlin.math.abs(v) >= 100f) "%.0f".format(v) else "%.1f".format(v)) + p.unit
}
