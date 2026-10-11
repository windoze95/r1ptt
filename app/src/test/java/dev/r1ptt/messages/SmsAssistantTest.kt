package dev.r1ptt.messages

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.OutcomeStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SmsAssistantTest {
    private lateinit var app: App
    private lateinit var controller: SmsController
    private lateinit var assistant: SmsAssistant
    private lateinit var transport: FakeTransport
    private val reports = mutableListOf<String>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        app.getSharedPreferences("messages", 0).edit().clear().commit()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        transport = FakeTransport()
        controller = SmsController(app, transport) { true }
        assistant = SmsAssistant(app, controller).apply { enabled = true }
    }

    private inner class FakeTransport : SmsTransport {
        val records = CopyOnWriteArrayList<SmsRecord>()
        override fun divide(body: String, subscription: Int) = listOf(body.take(3), body.drop(3))
        override fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>) {
            SmsStore(app).use { assertEquals(record, it.record(record.id)) }
            assertEquals(record.body, texts.joinToString(""))
            assertEquals(2, sent.size)
            assertEquals(2, delivery.size)
            records.add(record)
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Assistant completion did not arrive", condition())
    }

    /** Two worker/main round trips include work enqueued by the first main-thread callback. */
    private fun drain() {
        repeat(2) {
            var finished = false
            controller.recipients { _, _ -> finished = true }
            await { finished }
        }
    }

    private fun execute(command: String): SmsAssistant.Request {
        val request = requireNotNull(assistant.request(command))
        assistant.execute(request, { true }, reports::add)
        await { reports.isNotEmpty() }
        return request
    }

    private fun noSend() {
        drain()
        assertTrue(transport.records.isEmpty())
        SmsStore(app).use { assertTrue(it.threads().isEmpty()) }
        assertTrue(app.history.messages.isEmpty())
    }

    private fun attempt(): SmsRecord = SmsStore(app).use { it.record(transport.records.single().id)!! }

    @Test fun exactNumberSendsWithoutReviewAndOpensOnlyTheRecordedThread() {
        execute("Text +1 (555) 123-4567: Synthetic assistant message")
        assertEquals(1, transport.records.size)
        val record = transport.records.single()
        assertEquals("+15551234567", record.peer)
        assertEquals("Synthetic assistant message", record.body)
        assertEquals(listOf("Sending…"), reports)
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MessagesActivity::class.java.name, opened.component?.className)
        assertEquals(record.peer, opened.getStringExtra(SmsAssistant.THREAD))
        assertFalse(opened.hasExtra(SmsAssistant.RECIPIENT))
        assertFalse(opened.hasExtra(SmsAssistant.BODY))
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun uniqueSavedNameResolvesLocallyAndSendsTheExactBody() {
        SmsStore(app).use { it.addRecipient("Synthetic Person", "+15551234567") }
        execute("Please text synthetic person saying Meet at 4:30. Bring tea!")
        assertEquals(1, transport.records.size)
        assertEquals("+15551234567", transport.records.single().peer)
        assertEquals("Meet at 4:30. Bring tea!", transport.records.single().body)
        assertEquals(listOf("Sending…"), reports)
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun repeatedCompletionOfOneRequestCannotCreateAnotherAttempt() {
        val request = requireNotNull(assistant.request("Text +15551234567: Synthetic replay test"))
        assistant.execute(request, { true }, reports::add)
        assistant.execute(request, { true }, reports::add)
        await { reports.isNotEmpty() }
        assistant.execute(request, { true }, reports::add)
        drain()
        assertEquals(1, transport.records.size)
        assertEquals(listOf("Sending…"), reports)
        SmsStore(app).use { assertEquals(1, it.conversation("+15551234567").size) }
        assertNotNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun unknownNameAsksForARecipientWithoutSendingOrLeavingHome() {
        execute("Text Unknown Person that Synthetic clarification body")
        noSend()
        assertEquals(listOf("I don't have a saved number for that name. Say the full phone number, or save the name under Messages → Options → Saved recipients. No text was sent."), reports)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun duplicateNameWithDifferentNumbersAsksForAnExactNumberWithoutLeavingHome() {
        SmsStore(app).use {
            it.addRecipient("Synthetic Person", "+15551234567")
            it.addRecipient("Synthetic Person", "+15557654321")
        }
        execute("Text Synthetic Person that Synthetic ambiguous body")
        noSend()
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(reports.single().contains("full phone number"))
    }

    @Test fun disablingAssistantBeforeRecipientCallbackPreventsDispatch() {
        val request = requireNotNull(assistant.request("Text +15551234567: Synthetic disabled test"))
        assistant.execute(request, { true }, reports::add)
        assistant.enabled = false
        noSend()
        assertTrue(reports.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        assistant.enabled = true
        assistant.execute(request, { true }, reports::add)
        noSend() // Re-enabling cannot revive the consumed request.
    }

    @Test fun staleTurnBeforeRecipientCallbackPreventsDispatch() {
        var current = true
        val request = requireNotNull(assistant.request("Text +15551234567: Synthetic stale turn"))
        assistant.execute(request, { current }, reports::add)
        current = false
        noSend()
        assertTrue(reports.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        current = true
        assistant.execute(request, { current }, reports::add)
        noSend()
    }

    @Test fun deniedSmsPermissionReportsNoSendAndKeepsDatabaseEmpty() {
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        execute("Text +15551234567: Synthetic permission test")
        noSend()
        assertEquals(listOf("Enable SMS sending first. No text was sent."), reports)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun directAssistantSendPreservesAnIdenticalUnfinishedManualDraft() {
        val draft = SmsDraft("+15551234567", "Synthetic shared draft text")
        SmsStore(app).use { it.saveDraft(draft) }
        execute("Text ${draft.peer}: ${draft.body}")
        assertEquals(1, transport.records.size)
        SmsStore(app).use { assertEquals(draft, it.draft()) }
    }

    @Test fun handoffRemainsSendingUntilAllNativeSentCallbacksAndDoesNotClaimDelivery() {
        execute("Text +15551234567: Synthetic native status test")
        assertEquals(listOf("Sending…"), reports)
        assertEquals(SmsStatus.SENDING, attempt().status(System.currentTimeMillis()))
        assertEquals(OutcomeStatus.HANDOFF, app.outcomes.list().single().status)
        fun result(part: Int, delivery: Boolean = false) {
            val record = transport.records.single()
            val done = CountDownLatch(1)
            val id = SmsCallback(record.id, record.token, part, delivery)
            controller.result(Intent().setData(Uri.parse(id.uri())), Activity.RESULT_OK) { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        result(0)
        assertEquals(SmsStatus.SENDING, attempt().status(System.currentTimeMillis()))
        assertEquals(OutcomeStatus.HANDOFF, app.outcomes.list().single().status)
        result(1)
        assertEquals(SmsStatus.SENT, attempt().status(System.currentTimeMillis()))
        assertEquals(OutcomeStatus.SMS_SENT, app.outcomes.list().single().status)
        result(0, delivery = true) // An OK callback without a carrier PDU is not proof of delivery.
        result(1, delivery = true)
        assertEquals(SmsStatus.SENT, attempt().status(System.currentTimeMillis()))
        assertEquals(1, transport.records.size)
        assertEquals(OutcomeStatus.SMS_SENT, app.outcomes.list().single().status)
    }

    @Test fun nativeRadioFailureBecomesRetainedFailureWithoutRetry() {
        execute("Text +15551234567: Synthetic radio failure test")
        val record = transport.records.single()
        for (part in record.parts.indices) {
            val done = CountDownLatch(1)
            controller.result(Intent().setData(Uri.parse(SmsCallback(record.id, record.token, part, false).uri())),
                android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF) { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        assertEquals(OutcomeStatus.SMS_FAILED, app.outcomes.list().single().status)
        assertEquals(android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF, app.outcomes.list().single().code)
        assertEquals(1, transport.records.size)
        assertEquals(SmsStatus.FAILED, attempt().status(System.currentTimeMillis()))
    }

    @Test fun priorDraftOptInDoesNotEnableDirectSending() {
        assistant.enabled = false
        app.getSharedPreferences("messages", 0).edit().putBoolean("assistantDrafts", true).commit()
        assertFalse(assistant.enabled)
        assertNull(assistant.request("Text +15551234567: Synthetic old opt-in test"))
        noSend()
    }
}
