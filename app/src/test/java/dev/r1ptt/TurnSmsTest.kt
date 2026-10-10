package dev.r1ptt

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.messages.*
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.CallRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TurnSmsTest {
    private lateinit var app: App
    private lateinit var turns: TurnController
    private lateinit var sms: SmsController
    private lateinit var assistant: SmsAssistant
    private val records = CopyOnWriteArrayList<SmsRecord>()
    private val interpreted = CopyOnWriteArrayList<String>()
    private val text = "Could you send Sam a message saying Meet at six."
    private val action = SmsIntent.Send(SmsComposeAction("Sam", "Meet at six."))
    private var resolver: (AppConfig, String, CallRegistry) -> SmsIntent = { _, input, _ -> interpreted.add(input); action }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.deleteDatabase("messages.db")
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        assertTrue(app.radio.isOnline())
        sms = SmsController(app, object : SmsTransport {
            override fun divide(body: String, subscription: Int) = listOf(body)
            override fun send(record: SmsRecord, texts: List<String>, sent: ArrayList<PendingIntent>, delivery: ArrayList<PendingIntent>) {
                assertFalse(turns.busy)
                records.add(record)
            }
        }) { true }
        assistant = SmsAssistant(app, sms).apply { enabled = true }
        turns = TurnController(app, assistant) { cfg, input, calls -> resolver(cfg, input, calls) }
        App::class.java.getDeclaredField("turns").apply { isAccessible = true }.set(app, turns)
        SmsStore(app).use { it.addRecipient("Sam", "+15551234567") }
    }
    @After fun cleanup() { turns.shutdown() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Turn condition did not arrive", condition())
    }
    private fun drain() { repeat(2) { var done = false; sms.recipients { _, _ -> done = true }; await { done } } }
    @Suppress("UNCHECKED_CAST") private fun provider(id: String) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<AppConfig>
        state.value = state.value.copy(activeProvider = id, tts = state.value.tts.copy(enabled = false))
    }
    /** Enter at the completed STT boundary; no microphone or real provider is used. */
    private fun completedVoice(input: String) {
        val handle = TurnController::class.java.declaredMethods.single { it.name == "handleTranscript" }.apply { isAccessible = true }
        val block: suspend (Any) -> Unit = { turn -> suspendCoroutine { continuation ->
            val result = handle.invoke(turns, turn, input, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } }
        TurnController::class.java.declaredMethods.single { it.name == "launch" }.apply { isAccessible = true }
            .invoke(turns, 1L, false, OutcomeSource.VOICE, null, block)
    }

    @Test fun naturalTypedRequestDispatchesOnceWithoutReviewOrProseHistory() {
        turns.sendText(text)
        await { records.size == 1 && app.outcomes.list().single().status == OutcomeStatus.HANDOFF }
        drain()
        assertEquals(listOf(text), interpreted)
        assertEquals("+15551234567", records.single().peer)
        assertEquals("Meet at six.", records.single().body)
        assertTrue(app.history.messages.isEmpty())
        assertEquals(OutcomeSource.TYPED, app.outcomes.list().single().source)
        assertEquals(MessagesActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test fun completedVoiceRequestsUseTheSameExecutorForEveryChatProvider() {
        for ((index, id) in listOf("openai", "hermes", "custom").withIndex()) {
            provider(id)
            completedVoice(text)
            await { records.size == index + 1 && app.outcomes.list().last().status == OutcomeStatus.HANDOFF }
            drain()
            assertEquals(OutcomeSource.VOICE, app.outcomes.list().last().source)
        }
        assertEquals(listOf(text, text, text), interpreted)
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun ambiguousIntentNeverDispatchesOrStartsAProseChat() {
        resolver = { _, _, _ -> SmsIntent.Clarify }
        turns.sendText("Send that to her")
        await { app.outcomes.list().single().status == OutcomeStatus.CLARIFY }
        drain(); assertTrue(records.isEmpty()); assertTrue(app.history.messages.isEmpty())
        assertTrue(turns.state.value.note.contains("No text was sent"))
    }

    @Test fun providerFailureIsRetainedWithoutItsRawMessageOrFalsePromise() {
        resolver = { _, _, _ -> throw ApiError(401, "synthetic private provider detail") }
        turns.sendText(text)
        await { app.outcomes.list().single().status == OutcomeStatus.FAILED }
        assertEquals(OutcomeReason.AUTHORIZATION, app.outcomes.list().single().reason)
        assertEquals(401, app.outcomes.list().single().code)
        assertTrue(turns.state.value.note.contains("No text was sent"))
        assertFalse(app.getSharedPreferences("action_outcomes", 0).all.toString().contains("private provider"))
        drain(); assertTrue(records.isEmpty()); assertTrue(app.history.messages.isEmpty())
    }

    @Test fun cancellationWhileInterpretingCannotDispatchALateDecision() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        resolver = { _, _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); returned.countDown(); action }
        turns.sendText(text)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        turns.onDoubleTap(); release.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS))
        await { app.outcomes.list().single().status == OutcomeStatus.CANCELLED }
        drain(); assertTrue(records.isEmpty()); assertTrue(app.history.messages.isEmpty())
    }

    @Test fun disabledAssistantRejectsNaturalSendBeforeAnyProviderRequest() {
        assistant.enabled = false
        turns.sendText(text)
        await { app.outcomes.list().single().status == OutcomeStatus.DISABLED }
        assertTrue(interpreted.isEmpty()); drain(); assertTrue(records.isEmpty())
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun unavailableSmsPermissionCannotBecomeASuccessfulAssistantOutcome() {
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        turns.sendText(text)
        await { app.outcomes.list().single().status == OutcomeStatus.FAILED }
        assertEquals(OutcomeReason.UNAVAILABLE, app.outcomes.list().single().reason)
        drain(); assertTrue(records.isEmpty())
    }
}
