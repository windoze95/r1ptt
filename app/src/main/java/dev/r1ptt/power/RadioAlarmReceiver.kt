package dev.r1ptt.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.r1ptt.App
import kotlin.concurrent.thread

/** The idle alarm set by [RadioPolicy] when the screen goes off. */
class RadioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val pending = goAsync()
        thread(name = "radio-idle") {
            try {
                app.radio.onIdleAlarm(screenOn = app.screen.isScreenOn(), busy = app.turns.busy)
            } finally {
                pending.finish()
            }
        }
    }
}
