package dev.r1ptt.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.r1ptt.App
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class SmsStatusReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    @Before fun setup() { app.deleteDatabase("messages.db") }

    private fun deliver(code: Int, radioError: Int? = null): SmsRecord {
        val record = SmsRecord.outgoing("+15551234567", "Synthetic test only", 1, 1, 1000)
        SmsStore(app).use { it.outgoing(record, SmsDraft()) }
        val receiver = SmsStatusReceiver()
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod("create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val pending = create.invoke(null, code, null, null, false) as BroadcastReceiver.PendingResult
        ReflectionHelpers.setField(receiver, "mPendingResult", pending)
        val intent = Intent(app, SmsStatusReceiver::class.java).setData(Uri.parse(SmsCallback(record.id, record.token, 0, false).uri()))
        radioError?.let { intent.putExtra("errorCode", it) }
        assertEquals(code, receiver.resultCode)
        receiver.onReceive(app, intent)
        assertTrue(shadowOf(receiver).wentAsync())
        assertEquals("Android clears the receiver result after goAsync", 0, receiver.resultCode)
        shadowOf(pending).future.get(5, TimeUnit.SECONDS)
        return SmsStore(app).use { requireNotNull(it.record(record.id)) }
    }

    @Test fun frameworkSuccessIsCapturedBeforeGoAsyncAndPersistsAsSent() {
        val record = deliver(Activity.RESULT_OK)
        assertEquals(SmsStatus.SENT, record.status(2000))
        assertNull(record.parts.single().error)
        assertEquals(DeliveryPart.UNKNOWN, record.parts.single().delivery)
    }
    @Test fun frameworkFailureKeepsItsActualCodeAndRadioError() {
        val record = deliver(android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE, 42)
        assertEquals(SmsStatus.FAILED, record.status(2000))
        assertEquals(4, record.parts.single().error); assertEquals(42, record.parts.single().radioError)
    }
    @Test fun legacyZeroResultIsPreservedWithoutClaimingNonDelivery() {
        val old = SmsRecord.outgoing("+15551234567", "Synthetic", 1, 1, 1000)
            .copy(parts = listOf(SmsPart(sent = SentPart.FAILED, error = 0)))
        val restored = SmsRecord.decode(old.encode())
        assertEquals(SmsStatus.UNKNOWN, restored.status(2000))
        assertEquals(SentPart.FAILED, restored.parts.single().sent)
        assertEquals(0, restored.parts.single().error)
        assertTrue(SmsDiagnostics.attempt(restored).contains("goAsync"))
    }
}
