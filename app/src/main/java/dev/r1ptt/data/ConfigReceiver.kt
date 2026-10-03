package dev.r1ptt.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.r1ptt.App
import dev.r1ptt.power.DeviceSettings

/**
 * Lets tools/provision.sh push settings and API keys over adb, so nothing has to be typed on the
 * R1's tiny keyboard. The manifest guards it with android.permission.DUMP, which adb's shell user
 * holds and ordinary apps can't get.
 *
 *   adb shell am broadcast -n dev.r1ptt/.data.ConfigReceiver -a dev.r1ptt.CONFIG --es json_b64 <base64>
 *
 * The broadcast's result data is "ok" or "error: <why>".
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as App
        resultData = try {
            val b64 = requireNotNull(intent.getStringExtra("json_b64")) { "missing json_b64 extra" }
            val cfg = app.store.import(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
            DeviceSettings.apply(cfg)
            app.radio.applyBaseline()
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    companion object {
        const val ACTION = "dev.r1ptt.CONFIG"
    }
}
