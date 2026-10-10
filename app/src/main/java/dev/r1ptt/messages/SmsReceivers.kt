package dev.r1ptt.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.r1ptt.App

/** Manifest requires BROADCAST_SMS on the sender. No microphone, AI, or radio wake work here. */
class SmsIncomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        (context.applicationContext as App).sms.receive(intent) { pending.finish() }
    }
}

/** Non-exported: reachable only through the explicit PendingIntents given to Android's SMS service. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (SmsCallback.parse(intent.dataString) == null) return
        val pending = goAsync()
        (context.applicationContext as App).sms.result(intent, resultCode) { pending.finish() }
    }
}
