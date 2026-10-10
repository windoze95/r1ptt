package dev.r1ptt.messages

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/** Default handlers own provider persistence. Never scans/imports an existing inbox. */
class SmsSystemStore(private val context: Context, private val db: SmsStore) {
    fun sync(record: SmsRecord) {
        if (!record.systemOwned || !SmsRole.held(context)) return
        val resolver = context.contentResolver
        var uri = db.providerUri(record.id)?.let(Uri::parse)
        if (uri == null) {
            // Recover only this exact app-owned row after a crash between provider insert and journal commit.
            val id = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "address=? AND body=? AND date=? AND sub_id=? AND creator=?",
                arrayOf(record.peer, record.body, record.createdAt.toString(), record.subscriptionId.toString(), context.packageName), null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            uri = id?.let { ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it) }
                ?: resolver.insert(Telephony.Sms.CONTENT_URI, values(record).apply {
                    put(Telephony.Sms.ADDRESS, record.peer); put(Telephony.Sms.BODY, record.body)
                    put(Telephony.Sms.DATE, record.createdAt); put(Telephony.Sms.SUBSCRIPTION_ID, record.subscriptionId)
                    put(Telephony.Sms.READ, if (record.incoming) 0 else 1); put(Telephony.Sms.SEEN, if (record.incoming) 0 else 1)
                }) ?: error("System SMS copy was not saved")
            db.linked(record.id, uri.toString())
        }
        check(resolver.update(uri, values(record), null, null) == 1) { "System SMS status was not saved" }
        db.systemCopySaved(record.id)
    }

    private fun values(record: SmsRecord) = ContentValues().apply {
        put(Telephony.Sms.TYPE, if (record.incoming) Telephony.Sms.MESSAGE_TYPE_INBOX else when (record.status(System.currentTimeMillis())) {
            SmsStatus.FAILED -> Telephony.Sms.MESSAGE_TYPE_FAILED
            SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.DELIVERY_FAILED -> Telephony.Sms.MESSAGE_TYPE_SENT
            else -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
        })
        put(Telephony.Sms.STATUS, when (record.status(System.currentTimeMillis())) {
            SmsStatus.RECEIVED -> Telephony.Sms.STATUS_NONE
            SmsStatus.DELIVERED -> Telephony.Sms.STATUS_COMPLETE
            SmsStatus.FAILED, SmsStatus.DELIVERY_FAILED -> Telephony.Sms.STATUS_FAILED
            else -> Telephony.Sms.STATUS_PENDING
        })
        put(Telephony.Sms.ERROR_CODE, record.parts.firstNotNullOfOrNull { it.radioError ?: it.error } ?: 0)
    }
}
