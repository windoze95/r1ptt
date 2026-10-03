package dev.r1ptt.power

import dev.r1ptt.data.Config
import dev.r1ptt.sys.Root

/** Pushes the screen part of [Config.power] into Android's own settings (needs root). */
object DeviceSettings {
    fun apply(cfg: Config) {
        val p = cfg.power
        Root.async(
            listOf(
                "settings put system screen_off_timeout ${p.screenTimeoutSec.coerceIn(5, 600) * 1000}",
                "settings put system screen_brightness_mode 0",
                "settings put system screen_brightness ${p.brightness.coerceIn(1, 255)}",
            ).joinToString("; ")
        )
    }

    fun reboot() = Root.async("svc power reboot || reboot")

    fun powerOff() = Root.async("svc power shutdown || reboot -p")
}
