package dev.r1ptt.messages

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsRoleTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun manifestSuppliesEveryProtectedRoleComponentAndKeepsActualSendPrivate() {
        val pm = app.packageManager
        for (scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            val data = Uri.parse("$scheme:+15551234567")
            assertTrue(pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, data), 0).any { it.activityInfo.name == SmsComposeActivity::class.java.name })
            val service = pm.queryIntentServices(Intent(android.telephony.TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, data), 0).single { it.serviceInfo.name == SmsRespondService::class.java.name }.serviceInfo
            assertEquals(Manifest.permission.SEND_RESPOND_VIA_MESSAGE, service.permission)
            assertTrue(service.exported)
        }
        val sms = pm.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0).single { it.activityInfo.name == SmsIncomingReceiver::class.java.name }.activityInfo
        assertEquals(Manifest.permission.BROADCAST_SMS, sms.permission)
        val mms = pm.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).setType("application/vnd.wap.mms-message"), 0).single { it.activityInfo.name == MmsNoticeReceiver::class.java.name }.activityInfo
        assertEquals(Manifest.permission.BROADCAST_WAP_PUSH, mms.permission)
        assertFalse(pm.getActivityInfo(ComponentName(app, MessagesActivity::class.java), 0).exported)
        assertFalse(pm.getReceiverInfo(ComponentName(app, SmsStatusReceiver::class.java), 0).exported)
    }

    @Test fun externalSmsIntentParsesOneRecipientAndBodyWithoutExecutingIt() {
        assertEquals(SmsDraft("+15551234567", "hello world!"), SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+15551234567?body=hello%20world!"))))
        assertEquals(SmsDraft("+15551234567", ""), SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567"))))
        assertEquals(SmsDraft("+15551234567", "Call reply"), SmsExternalDraft.parse(Intent(android.telephony.TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, Uri.parse("smsto:+15551234567")).putExtra(Intent.EXTRA_TEXT, "Call reply")))
    }

    @Test fun externalMmsGroupsDialStringsAndOversizedBodiesAreRejected() {
        for (uri in listOf("mms:+15551234567", "mmsto:+15551234567", "smsto:15551234567;15557654321", "sms:*21*12345#", "sms:someone@example.com", "sms:+15551234567#fragment"))
            assertNull(uri, SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse(uri))))
        assertNull(SmsExternalDraft.parse(Intent(Intent.ACTION_VIEW, Uri.parse("sms:+15551234567"))))
        assertNull(SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567")).putExtra("sms_body", "x".repeat(1601))))
        assertNull(SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567")).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/picture"))))
        assertNull(SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567?body=one&body=two"))))
        assertNull(SmsExternalDraft.parse(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:+15551234567?cc=15557654321"))))
    }

    @Test fun diagnosticFailureNamesAreActionableWithoutClaimingCarrierEntitlement() {
        val missingDefault = android.telephony.SmsManager.RESULT_NO_DEFAULT_SMS_APP
        assertEquals("RESULT_NO_DEFAULT_SMS_APP", SmsDiagnostics.errorName(missingDefault))
        assertTrue(SmsDiagnostics.advice(missingDefault).contains("default SMS app"))
        val record = SmsRecord.outgoing("+15551234567", "Synthetic", 1, 1, 1000).sent(0, false, missingDefault, 42)
        val details = SmsDiagnostics.attempt(record)
        assertTrue(details.contains("Recorded subscription: 1")); assertTrue(details.contains("Radio error: 42"))
        assertFalse(details.contains(record.peer)); assertFalse(details.contains(record.body)); assertFalse(details.contains(record.token))
        assertTrue(SmsDiagnostics.advice(1).contains("does not identify a plan restriction"))
    }
}
