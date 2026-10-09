package dev.r1ptt.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.r1ptt.App

/** Non-exported: only the explicit mutable PendingIntent handed to PackageInstaller reaches this. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as App).updates.installResult(intent)
    }
}
