package dev.r1ptt.messages

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager

/** Injectable boundary: tests use a recorder, never a modem. */
interface SmsTransport {
    fun divide(body: String, subscription: Int): List<String>
    fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>)
}

class AndroidSmsTransport(private val context: Context) : SmsTransport {
    private fun manager(subscription: Int) = context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
    override fun divide(body: String, subscription: Int): List<String> = manager(subscription).divideMessage(body).toList()
    override fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>) {
        val manager = manager(record.subscriptionId)
        if (texts.size == 1) manager.sendTextMessage(record.peer, null, texts.single(), sent.single(), delivery.single())
        else manager.sendMultipartTextMessage(record.peer, null, ArrayList(texts), sent, delivery)
    }
}
