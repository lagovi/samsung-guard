package com.cybermiller.guard

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HealthMonitor {
    /** Returns a compact human-readable device health line. */
    fun snapshot(ctx: Context): String {
        val sb = StringBuilder()

        // Battery
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val b = ctx.registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        sb.append("🔋 Battery: $level%")
        if (temp > 0) sb.append(", ${"%.1f".format(temp / 10.0)}°C")
        sb.append(if (plugged != 0) " (charging)" else " (discharging)")
        sb.append("\n")

        // Storage (/data)
        try {
            val sf = StatFs("/data")
            val freeGb = sf.availableBytes / 1e9
            val totalGb = sf.totalBytes / 1e9
            sb.append("💾 Storage: ${"%.1f".format(freeGb)}/${"%.0f".format(totalGb)} GB free\n")
        } catch (_: Exception) {}

        // RAM
        try {
            val mi = File("/proc/meminfo").readLines()
            fun kb(key: String) = mi.firstOrNull { it.startsWith(key) }
                ?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: 0L
            val total = kb("MemTotal")
            val avail = kb("MemAvailable")
            if (total > 0) {
                val freeMb = avail / 1024
                val totalMb = total / 1024
                sb.append("🧠 RAM: $freeMb/$totalMb MB free\n")
            }
        } catch (_: Exception) {}

        // Load
        try {
            val load = File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString(" ")
            sb.append("⚙️ Load: $load\n")
        } catch (_: Exception) {}

        // Thermal
        try {
            val t = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toIntOrNull()
            if (t != null && t > 1000) sb.append("🌡 SoC: ${t / 1000.0}°C\n")
        } catch (_: Exception) {}

        return sb.toString().trimEnd()
    }

    fun report(ctx: Context) {
        TelegramClient.sendText("<b>📊 Health</b>\n" + snapshot(ctx))
    }
}