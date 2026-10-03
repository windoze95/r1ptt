package dev.r1ptt.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One CSV row per screen on/off transition. Screen-off rows open idle periods and the next
 * screen-on closes them, so idle drain per hour falls out of consecutive rows without the app
 * waking the device to sample anything. tools/battery-report.sh reads the same file.
 */
class BatteryLog(dir: File) {
    private val file = File(dir, "battery.csv")
    private val io = Executors.newSingleThreadExecutor()

    fun record(ctx: Context, event: String) {
        val level = ctx.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val plugged = (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val wifi = ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val row = "${System.currentTimeMillis()},${SystemClock.elapsedRealtime()},$event,$level,$plugged,$wifi\n"
        io.execute {
            runCatching {
                if (!file.exists()) file.writeText(HEADER)
                if (file.length() > MAX_BYTES) file.writeText(HEADER + file.readLines().drop(1).takeLast(2000).joinToString("\n", postfix = "\n"))
                file.appendText(row)
            }
        }
    }

    fun summary(): String = runCatching { summarize(file.readLines()) }.getOrDefault("No battery data yet.")

    companion object {
        const val HEADER = "wall_ms,elapsed_ms,event,level,plugged,wifi\n"
        private const val MAX_BYTES = 512 * 1024

        /**
         * Idle drain over unplugged screen-off periods of at least 30 minutes (shorter ones are
         * mostly rounding noise: the level only moves in whole percent).
         */
        fun summarize(lines: List<String>): String {
            data class Row(val elapsed: Long, val event: String, val level: Int, val plugged: Boolean, val wifi: Boolean)
            val rows = lines.drop(1).mapNotNull { l ->
                val f = l.split(",")
                if (f.size < 6) null else runCatching {
                    Row(f[1].toLong(), f[2], f[3].toInt(), f[4].toBoolean(), f[5].toBoolean())
                }.getOrNull()
            }
            var hours = 0.0
            var drop = 0
            var periods = 0
            for ((a, b) in rows.zipWithNext()) {
                if (a.event != "screen_off" || a.plugged || b.plugged) continue
                val h = (b.elapsed - a.elapsed) / 3_600_000.0
                if (h < 0.5 || b.level > a.level) continue // too short, or charged in between
                hours += h
                drop += a.level - b.level
                periods++
            }
            if (periods == 0) return "Not enough unplugged screen-off time logged yet (need 30+ minute periods)."
            return String.format(
                Locale.US, "Idle drain: %.2f%%/hour over %.1f h in %d screen-off periods (≈ %.0f h from full).",
                drop / hours, hours, periods, if (drop == 0) Double.POSITIVE_INFINITY else 100 / (drop / hours),
            )
        }
    }
}
