package dev.r1ptt

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.PowerManager
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.data.Msg
import dev.r1ptt.messages.*
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.CallRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration
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
    private var transportFailure: Exception? = null
    private var transportCalls = 0
    private val interpreted = CopyOnWriteArrayList<String>()
    private val text = "Could you send Sam a message saying Meet at six."
    private val action = SmsIntent.Send(SmsComposeAction("Sam", "Meet at six."))
    private val fixtures = mapOf(
        text to action,
        "Text Unlisted: Meet at six." to SmsIntent.Send(SmsComposeAction("Unlisted", "Meet at six.")),
    )
    private var resolver: (AppConfig, String, CallRegistry) -> SmsIntent = { _, input, _ ->
        interpreted.add(input)
        requireNotNull(fixtures[input]) { "Unexpected synthetic resolver input" }
    }

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
                transportCalls++
                transportFailure?.let { throw it }
                records.add(record)
            }
        }) { true }
        assistant = SmsAssistant(app, sms).apply { enabled = true }
        turns = TurnController(app, assistant) { cfg, input, calls -> resolver(cfg, input, calls) }
        App::class.java.getDeclaredField("turns").apply { isAccessible = true }.set(app, turns)
        SmsStore(app).use { it.addRecipient("Sam", "+15551234567") }
        configure { it.copy(tts = it.tts.copy(enabled = false)) }
    }
    @After fun cleanup() { turns.shutdown() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Turn condition did not arrive", condition())
    }
    private fun drain() { repeat(2) { var done = false; sms.recipients { _, _ -> done = true }; await { done } } }
    @Suppress("UNCHECKED_CAST") private fun configure(update: (AppConfig) -> AppConfig) {
        val state = app.store.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(app.store) as MutableStateFlow<AppConfig>
        state.value = update(state.value)
    }
    private fun provider(id: String) = configure { it.copy(activeProvider = id) }
    private fun speechEndpoint(server: MockWebServer) = configure {
        it.copy(tts = it.tts.copy(enabled = true, endpoint = it.tts.endpoint.copy(baseUrl = server.url("/v1").toString())))
    }
    private fun intentEndpoint(server: MockWebServer) {
        configure { it.copy(activeProvider = "openai", providers = it.providers +
            ("openai" to it.providers.getValue("openai").copy(baseUrl = server.url("/v1").toString()))) }
        resolver = SmsIntentClient()::resolve
    }
    private fun intentResponse(recipient: String, body: String, kind: String = "sms"): MockResponse {
        val decision = JSONObject().put("kind", kind).put("recipient", recipient).put("body", body)
        val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", decision.toString())).put("finish_reason", "stop")))
        return MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString())
    }
    private fun assertIntentRequest(server: MockWebServer, input: String): String {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(input, messages.getJSONObject(1).getString("content"))
        return messages.getJSONObject(0).getString("content")
    }
    private fun assertGenericReply() {
        assertEquals(Msg.ASSISTANT, app.history.messages.single().role)
        assertTrue(app.history.messages.single().text.contains("No text was sent"))
        assertFalse(app.history.messages.single().text.contains(text))
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

    @Test fun naturalTypedRequestDispatchesOnceWithoutReviewOrSmsContentInChatHistory() {
        turns.sendText(text)
        await { records.size == 1 && app.outcomes.list().single().status == OutcomeStatus.HANDOFF }
        drain()
        assertEquals(listOf(text), interpreted)
        assertEquals("+15551234567", records.single().peer)
        assertEquals("Meet at six.", records.single().body)
        assertEquals(listOf("Sending…"), app.history.messages.map { it.text })
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
        assertEquals(listOf("Sending…", "Sending…", "Sending…"), app.history.messages.map { it.text })
    }

    @Test fun bodylessCompletedVoiceRequestUsesRealIntentClientToComposeAndDispatchOnce() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            val input = "send a text to +15551234567"
            val generated = "Hi, just checking in!"
            assertFalse(input.contains(generated))
            server.enqueue(intentResponse("+15551234567", generated))
            completedVoice(input)
            await { records.size == 1 && app.outcomes.list().single().status == OutcomeStatus.HANDOFF && !turns.busy }
            drain()
            assertIntentRequest(server, input)
            assertEquals(1, server.requestCount)
            assertEquals(1, transportCalls)
            assertEquals("+15551234567", records.single().peer)
            assertEquals(generated, records.single().body)
            assertEquals(OutcomeSource.VOICE, app.outcomes.list().single().source)
            assertEquals(listOf("Sending…"), app.history.messages.map { it.text })
            assertEquals(MessagesActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
            assertNull(shadowOf(app).nextStartedActivity)
        }
    }

    @Test fun naturalTellNameVoiceRequestSendsTheProvidersComposedBodyToTheLocalRecipient() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            SmsStore(app).use { it.addRecipient("Yana", "+15557654321") }
            val input = "Tell Yana I'm on my way"
            val generated = "Hey Yana, I'm heading over now!"
            assertFalse(input.contains(generated))
            server.enqueue(intentResponse("Yana", generated))
            completedVoice(input)
            await { records.size == 1 && !turns.busy }
            drain()
            assertIntentRequest(server, input)
            assertEquals(1, server.requestCount)
            assertEquals(1, transportCalls)
            assertEquals("+15557654321", records.single().peer)
            assertEquals(generated, records.single().body)
            assertEquals(OutcomeStatus.HANDOFF, app.outcomes.list().single().status)
            assertEquals(listOf("Sending…"), app.history.messages.map { it.text })
        }
    }

    @Test fun formerlyLiteralVoiceSyntaxStillUsesTheRealProviderAndItsComposedMessage() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            val input = "Text Sam: Meet at six."
            val generated = "Hi Sam, let's meet at six."
            server.enqueue(intentResponse("Sam", generated))
            completedVoice(input)
            await { records.size == 1 && !turns.busy }
            drain()
            assertIntentRequest(server, input)
            assertEquals(1, server.requestCount)
            assertEquals(1, transportCalls)
            assertEquals("+15551234567", records.single().peer)
            assertEquals(generated, records.single().body)
        }
    }

    @Test fun cancellationWhileRealIntentHttpResponseIsPendingCannotDispatch() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            val input = "Send a text to +15551234567"
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            completedVoice(input)
            await { server.requestCount == 1 && turns.busy }
            assertIntentRequest(server, input)
            turns.onDoubleTap()
            await { app.outcomes.list().single().status == OutcomeStatus.CANCELLED && !turns.busy }
            drain()
            assertEquals(1, server.requestCount)
            assertEquals(0, transportCalls)
            assertTrue(records.isEmpty())
            assertTrue(app.history.messages.isEmpty())
            assertNull(shadowOf(app).nextStartedActivity)
        }
    }

    @Test fun explicitExactAndWordForWordVoiceRequestsPreserveTheWholeRequestedBody() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            val body = "Meet at six. Bring tea!"
            for ((index, control) in listOf("exactly", "word for word").withIndex()) {
                val input = "Text Sam $control: $body"
                server.enqueue(intentResponse("Sam", body, "sms_exact"))
                completedVoice(input)
                await { records.size == index + 1 && !turns.busy }
                drain()
                val instructions = assertIntentRequest(server, input)
                assertTrue(instructions.contains("sms_exact"))
                assertEquals(body, records.last().body)
                assertEquals("+15551234567", records.last().peer)
                assertEquals(OutcomeStatus.HANDOFF, app.outcomes.list().last().status)
            }
            assertEquals(2, server.requestCount)
            assertEquals(2, transportCalls)
            assertEquals(listOf("Sending…", "Sending…"), app.history.messages.map { it.text })
        }
    }

    @Test fun exactVoiceRequestsRejectComposedModeAndRewrittenOrShortenedExactResponses() {
        MockWebServer().use { server ->
            server.start(); intentEndpoint(server)
            val body = "Meet at six. Bring tea!"
            val input = "Text Sam exactly: $body"
            val responses = listOf(
                intentResponse("Sam", body, "sms"),
                intentResponse("Sam", "Let's meet at six and have tea.", "sms_exact"),
                intentResponse("Sam", "Meet at six.", "sms_exact"),
            )
            for ((index, response) in responses.withIndex()) {
                server.enqueue(response)
                completedVoice(input)
                await { app.outcomes.list().size == index + 1 &&
                    app.outcomes.list().last().status == OutcomeStatus.FAILED && !turns.busy }
                drain()
                assertIntentRequest(server, input)
                assertEquals(0, transportCalls)
                assertTrue(records.isEmpty())
                assertTrue(app.history.messages.last().text.contains("No text was sent"))
                assertNull(shadowOf(app).nextStartedActivity)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun clarificationRemainsReadableAfterTheStatusNoticeClearsWithoutRetainingTheRequest() {
        resolver = { _, _, _ -> SmsIntent.Clarify }
        turns.sendText("Send that to her")
        await { app.outcomes.list().single().status == OutcomeStatus.CLARIFY && !turns.busy }
        drain(); assertTrue(records.isEmpty()); assertGenericReply()
        assertEquals(OutcomeReason.REQUEST, app.outcomes.list().single().reason)
        assertFalse(app.history.messages.single().text.contains("Send that to her"))
        assertTrue(turns.state.value.note.contains("No text was sent"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("", turns.state.value.note)
        assertGenericReply()
    }

    @Test fun providerFailureIsRetainedWithoutItsRawMessageOrFalsePromise() {
        resolver = { _, _, _ -> throw ApiError(401, "synthetic private provider detail") }
        turns.sendText(text)
        await { app.outcomes.list().single().status == OutcomeStatus.FAILED && !turns.busy }
        assertEquals(OutcomeReason.AUTHORIZATION, app.outcomes.list().single().reason)
        assertEquals(401, app.outcomes.list().single().code)
        assertTrue(turns.state.value.note.contains("No text was sent"))
        assertFalse(app.getSharedPreferences("action_outcomes", 0).all.toString().contains("private provider"))
        drain(); assertTrue(records.isEmpty()); assertGenericReply()
        assertFalse(app.history.messages.toString().contains("private provider"))
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

    @Test fun disabledAssistantExplainsLiteralSendWithoutAnyProviderRequest() {
        assistant.enabled = false
        turns.sendText("Text Sam: Meet at six.")
        await { app.outcomes.list().single().status == OutcomeStatus.DISABLED }
        assertTrue(interpreted.isEmpty()); drain(); assertTrue(records.isEmpty())
        assertGenericReply()
    }

    @Test fun ordinaryInstructionsReachChatWithAssistantSendingEnabledOrDisabled() {
        MockWebServer().use { server ->
            server.start()
            configure { it.copy(providers = it.providers + ("openai" to it.provider.copy(baseUrl = server.url("/v1").toString()))) }
            resolver = { _, input, _ -> SmsIntent.decode("""{"kind":"chat"}""", input) }
            for (enabled in listOf(true, false)) {
                assistant.enabled = enabled
                app.history.newConversation()
                val reply = "Here is one sentence."
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
                    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply))
                        .put("finish_reason", "stop"))).toString()))
                turns.sendText("Reply in one sentence.")
                await { app.history.messages.lastOrNull()?.text == reply && !turns.busy }
                val request = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals("Reply in one sentence.", request.getJSONArray("messages").getJSONObject(1).getString("content"))
                assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content")
                    .contains(if (enabled) "sending is enabled" else "sending is disabled"))
                assertEquals(OutcomeStatus.COMPLETED, app.outcomes.list().last().status)
            }
            drain(); assertTrue(records.isEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun completedVoiceClarificationUsesSpeechAndTheNextTurnStillWorks() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setBody(""))
            resolver = { _, _, _ -> SmsIntent.Clarify }
            completedVoice(text)
            await { server.requestCount == 1 && !turns.busy }
            val speech = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/audio/speech", speech.path)
            assertEquals(app.history.messages.single().text, JSONObject(speech.body.readUtf8()).getString("input"))
            assertGenericReply(); assertTrue(records.isEmpty())
            assertEquals(OutcomeReason.REQUEST, app.outcomes.list().single().reason)
            server.enqueue(MockResponse().setBody(""))
            resolver = { _, _, _ -> action }
            completedVoice(text)
            await { records.size == 1 && !turns.busy }
            drain(); assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedClarificationSpeechLeavesTheExplanationVisibleAndDoesNotBlockTheNextTurn() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic private speech error"))
            resolver = { _, _, _ -> SmsIntent.Clarify }
            completedVoice(text)
            await { server.requestCount == 1 && !turns.busy }
            assertGenericReply(); assertTrue(records.isEmpty())
            assertEquals(OutcomeStatus.CLARIFY, app.outcomes.list().single().status)
            assertFalse(app.history.messages.toString().contains("private speech"))
            server.enqueue(MockResponse().setBody(""))
            resolver = { _, _, _ -> action }
            completedVoice(text)
            await { records.size == 1 && !turns.busy }
        }
    }

    @Test fun cancellingClarificationSpeechCannotSendOrLeaveTheTurnBusy() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            resolver = { _, _, _ -> SmsIntent.Clarify }
            completedVoice(text)
            await { server.requestCount == 1 && turns.state.value.phase == Phase.SPEAKING }
            turns.onDoubleTap()
            await { !turns.busy }
            drain(); assertTrue(records.isEmpty()); assertTrue(app.history.messages.isEmpty())
        }
    }

    @Test fun unavailableSmsPermissionCannotBecomeASuccessfulAssistantOutcome() {
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        turns.sendText(text)
        await { app.outcomes.list().single().status == OutcomeStatus.FAILED }
        assertEquals(OutcomeReason.UNAVAILABLE, app.outcomes.list().single().reason)
        drain(); assertTrue(records.isEmpty())
    }

    @Test fun unknownRecipientAfterRecognizedVoiceCommandGetsPersistentSpokenClarification() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setBody(""))
            completedVoice("Text Unlisted: Meet at six.")
            await { server.requestCount == 1 && !turns.busy }
            assertGenericReply()
            assertTrue(app.history.messages.single().text.contains("full phone number"))
            assertFalse(app.history.messages.single().text.contains("Unlisted"))
            assertFalse(app.history.messages.single().text.contains("Meet at six."))
            assertEquals(app.history.messages.single().text, JSONObject(server.takeRequest().body.readUtf8()).getString("input"))
            assertEquals(OutcomeReason.RECIPIENT, app.outcomes.list().single().reason)
            assertEquals(OutcomeStatus.CLARIFY, app.outcomes.list().single().status)
            assertEquals(0, transportCalls)
            assertNull(shadowOf(app).nextStartedActivity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertGenericReply()
        }
    }

    @Test fun localSendRejectionIsSpokenWithoutClaimingOrAttemptingDispatch() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setBody(""))
            shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
            completedVoice(text)
            await { server.requestCount == 1 && !turns.busy }
            assertGenericReply()
            assertTrue(app.history.messages.single().text.contains("Enable SMS sending first"))
            assertEquals(OutcomeStatus.FAILED, app.outcomes.list().single().status)
            assertEquals(0, transportCalls)
        }
    }

    @Test fun uncertainTransportOutcomeIsSpokenOnceAndNeverRetried() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setBody(""))
            transportFailure = java.io.IOException("synthetic private transport detail")
            completedVoice(text)
            await { server.requestCount == 1 && !turns.busy }
            assertEquals(1, transportCalls)
            assertEquals(OutcomeStatus.SMS_UNKNOWN, app.outcomes.list().single().status)
            assertEquals("Send status is uncertain. Check with the recipient before trying again.", app.history.messages.single().text)
            assertEquals(app.history.messages.single().text, JSONObject(server.takeRequest().body.readUtf8()).getString("input"))
            drain(); assertEquals(1, transportCalls)
        }
    }

    @Test fun cancellingRecipientFeedbackReleasesTheTurnWithoutStartingAnSms() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            completedVoice("Text Unlisted: Meet at six.")
            await { server.requestCount == 1 && turns.state.value.phase == Phase.SPEAKING }
            turns.onDoubleTap()
            await { !turns.busy }
            drain(); assertEquals(0, transportCalls)
            assertEquals(OutcomeStatus.CLARIFY, app.outcomes.list().single().status)
        }
    }

    @Test fun pressOnPrivateMessagesScreenStopsFeedbackAndReleasesTheWakeLock() {
        MockWebServer().use { server ->
            server.start(); speechEndpoint(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            completedVoice(text)
            await { records.size == 1 && server.requestCount == 1 && turns.state.value.phase == Phase.SPEAKING }
            val activity = Robolectric.buildActivity(MessagesActivity::class.java, shadowOf(app).nextStartedActivity).setup()
            val wake = app.screen.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }.get(app.screen) as PowerManager.WakeLock
            assertTrue(wake.isHeld)
            assertFalse(activity.get().canDictate())
            turns.onPress(1000); turns.onShortRelease(); turns.onTap()
            await { !turns.busy }
            assertFalse(wake.isHeld)
            drain(); assertEquals(1, transportCalls)
            assertEquals(OutcomeStatus.HANDOFF, app.outcomes.list().single().status)
            activity.pause().stop().destroy()
        }
    }
}
