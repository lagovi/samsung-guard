package com.cybermiller.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    // The sample is cheap (a few /proc reads, one StatFs, one sticky broadcast;
    // no wake-ups — the loop only runs while the device is awake), so 10 min
    // gives dense graphs at negligible CPU/battery cost.
    private const val SAMPLE_INTERVAL_MS = 600_000L
    private const val RETENTION_MS = 30L * 24 * 3600 * 1000   // 30 days
    private const val GRAPH_DAYS = 14L
    private const val GAP_MS = 2.5f * 3600 * 1000             // break lines across sleep gaps

    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        running = true
        Log.i(TAG, "health monitor start (hourly, awake only)")
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
            "sto=${s.storageFreeMb / 1024}GB ram=${s.ramFreeMb}MB load=${s.load1}")
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

        val socTemp = try {
            val t = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toIntOrNull() ?: 0
            if (t > 1000) t / 10 else 0     // millidegrees → tenths of °C
        } catch (_: Exception) { 0 }

        return HealthStore.Sample(
            ts = System.currentTimeMillis(),
            battery = level,
            charging = charging,
            battTemp = battTemp,        // EXTRA_TEMPERATURE is already tenths of °C
            storageFreeMb = storageFreeMb,
            ramFreeMb = ramFreeMb,
            load1 = load1,
            socTemp = socTemp,
        )
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
        if (s.socTemp > 0) sb.append("🌡 SoC: ${"%.1f".format(s.socTemp / 10.0)}°C\n")
        return sb.toString().trimEnd()
    }

    /** Full /health reply: current status text + history graph photo. */
    fun sendReport(ctx: Context) {
        TelegramClient.sendText("<b>📊 Текущее состояние</b>\n" + currentText(ctx))
        val since = System.currentTimeMillis() - GRAPH_DAYS * 24 * 3600 * 1000
        val samples = HealthStore.since(ctx, since)
        if (samples.size < 3) {
            TelegramClient.sendText(
                "Графики появятся, когда накопятся замеры (раз в час, пока телефон не спит). " +
                    "Сейчас точек: ${samples.size}.")
            return
        }
        val png = drawGraphs(ctx, samples)
        if (png != null) TelegramClient.sendPhoto(png, "📊 Здоровье за $GRAPH_DAYS дней (замер раз в час, пока телефон не спит)")
        else TelegramClient.sendText("❌ Не удалось построить графики")
    }

    // ---------------- graphs ----------------

    private class Panel(
        val title: String, val unit: String, val color: Int,
        val values: List<Float>,
        /** Fixed top of the scale (e.g. 100% for battery); null = max*1.08. */
        val fixedMax: Float? = null,
    )

    /** Renders battery / battery-temp / storage / RAM panels into one PNG. */
    private fun drawGraphs(ctx: Context, samples: List<HealthStore.Sample>): File? {
        return try {
            val t0 = samples.first().ts
            val t1 = samples.last().ts
            val span = (t1 - t0).coerceAtLeast(1L)

            val panels = listOf(
                Panel("🔋 Батарея, %", "%", Color.rgb(76, 217, 100),
                    samples.map { it.battery.toFloat() }, fixedMax = 100f),
                Panel("🌡 Температура батареи, °C", "°C", Color.rgb(255, 149, 0),
                    samples.map { it.battTemp / 10f }),
                Panel("💾 Свободно /data, ГБ", " ГБ", Color.rgb(90, 200, 250),
                    samples.map { it.storageFreeMb / 1024f }),
                Panel("🧠 Свободно RAM, МБ", " МБ", Color.rgb(175, 122, 255),
                    samples.map { it.ramFreeMb.toFloat() }),
            )

            val w = 1000
            val panelH = 320
            val padL = 20f
            val padR = 150f
            val top = 16f
            val h = (panels.size * panelH + 32).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(22, 24, 30))

            val bgPaint = Paint().apply { color = Color.rgb(30, 33, 41) }
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

            val dayFmt = SimpleDateFormat("dd.MM", Locale.getDefault())

            panels.forEachIndexed { pi, p ->
                val y0 = top + pi * panelH
                val plotL = padL
                val plotR = w - padR
                val plotT = y0 + 44f
                val plotB = y0 + panelH - 34f

                canvas.drawRect(plotL, y0 + 6f, w - 10f, y0 + panelH - 6f, bgPaint)
                canvas.drawText(p.title, padL + 10f, y0 + 34f, titlePaint)

                val maxV = p.values.maxOrNull() ?: 0f
                val lo = 0f                                  // always start from zero
                val hi = p.fixedMax ?: (maxV * 1.08f).coerceAtLeast(1f)
                val range = (hi - lo).coerceAtLeast(1e-6f)

                fun x(ts: Long) = plotL + (ts - t0).toFloat() / span * (plotR - plotL)
                fun y(v: Float) = plotB - (v - lo) / range * (plotB - plotT)

                // "0" anchor (lo is always zero), centred on its level
                canvas.drawText(fmt(p, 0f), plotR + 8f, plotB + 8f, labelPaint)

                // polyline, broken across sleep gaps
                linePaint.color = p.color
                dotPaint.color = p.color
                var path: Path? = null
                var prevTs = 0L
                for (i in samples.indices) {
                    val s = samples[i]
                    val px = x(s.ts); val py = y(p.values[i])
                    if (path == null || (prevTs > 0 && s.ts - prevTs > GAP_MS)) {
                        path?.let { canvas.drawPath(it, linePaint) }
                        path = Path().apply { moveTo(px, py) }
                    } else {
                        path.lineTo(px, py)
                    }
                    prevTs = s.ts
                }
                path?.let { canvas.drawPath(it, linePaint) }

                // min / max point markers; a marker coinciding with the newest
                // point is skipped — the last-value label already covers it
                val lastIdx = samples.size - 1
                if (p.values.distinct().size > 1) {
                    val maxIdx = p.values.indices.maxByOrNull { p.values[it] } ?: -1
                    val minIdx = p.values.indices.minByOrNull { p.values[it] } ?: -1
                    for ((idx, isMax) in listOf(maxIdx to true, minIdx to false)) {
                        if (idx < 0 || idx == lastIdx) continue
                        if (!isMax && idx == maxIdx) continue   // flat range, one marker is enough
                        val px = x(samples[idx].ts); val py = y(p.values[idx])
                        canvas.drawCircle(px, py, 4f, dotPaint)
                        val tx = if (px > plotR - 120f) px - 84f else px + 10f
                        val ty = if (isMax) py - 12f
                        else if (py > plotB - 26f) py - 12f
                        else py + 26f
                        canvas.drawText(fmt(p, p.values[idx]), tx, ty, labelPaint)
                    }
                }

                // last value dot + label
                val last = samples.last()
                val lx = x(last.ts); val ly = y(p.values.last())
                canvas.drawCircle(lx, ly, 5f, dotPaint)
                canvas.drawText(fmt(p, p.values.last()), lx - 70f, ly - 12f, labelPaint)

                // day ticks
                val cal = Calendar.getInstance()
                cal.timeInMillis = t0
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                while (cal.timeInMillis <= t1) {
                    val tx = x(cal.timeInMillis)
                    if (tx > plotL + 30f && tx < plotR - 30f) {
                        canvas.drawLine(tx, plotT, tx, plotB, gridPaint)
                        canvas.drawText(dayFmt.format(Date(cal.timeInMillis)), tx - 30f, y0 + panelH - 12f, labelPaint)
                    }
                    cal.add(Calendar.DAY_OF_MONTH, 1)
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
