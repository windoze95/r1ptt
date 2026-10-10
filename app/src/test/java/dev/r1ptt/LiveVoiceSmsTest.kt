package dev.r1ptt

import dev.r1ptt.audio.PcmPlayer
import dev.r1ptt.messages.SmsAssistant
import dev.r1ptt.messages.SmsComposeAction
import dev.r1ptt.net.LiveProtocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LiveVoiceSmsTest {
    private lateinit var app: App
    private lateinit var voice: LiveVoice
    private lateinit var tracker: LiveTracker
    private val requests = mutableListOf<SmsAssistant.Request>()
    private val states = mutableListOf<TurnState>()
    private val failures = mutableListOf<String>()
    private var settles = 0
    private val command = "Text +15551234567: Synthetic completed voice command"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.smsAssistant.enabled = true
        voice = LiveVoice(app, publish = states::add, fail = failures::add, settled = { settles++ }, sendSms = {
            // The handoff must follow the normal idle/settle boundary, not an active voice turn.
            assertFalse(voice.busy)
            assertEquals(Phase.IDLE, states.last().phase)
            assertTrue(settles > 0)
            requests.add(it)
        })
        tracker = field("tracker") as LiveTracker
    }

    @After fun cleanup() {
        voice.reset()
        (field("player") as PcmPlayer).release()
    }

    private fun field(name: String): Any? = LiveVoice::class.java.getDeclaredField(name).apply { isAccessible = true }.get(voice)

    private fun tick() {
        LiveVoice::class.java.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(voice)
    }

    private fun event(event: LiveProtocol.Event) {
        LiveVoice::class.java.getDeclaredMethod("handle", LiveProtocol.Event::class.java)
            .apply { isAccessible = true }.invoke(voice, event)
    }

    /** Inject accepted transcript events without starting a microphone, player or network session. */
    private fun begin(heard: String, said: String = "I will attempt the text after this turn.") {
        LiveVoice::class.java.getDeclaredField("active").apply { isAccessible = true }.setBoolean(voice, true)
        tracker.hold()
        event(LiveProtocol.Event.Heard(heard))
        event(LiveProtocol.Event.Said(said))
        assertNull(field("session"))
        assertFalse(voice.capturing)
    }

    /** Model already-played speech in the tracker; no PCM reaches an AudioTrack. */
    private fun releaseAndReply() {
        tracker.release()
        tracker.audio(speech = true)
    }

    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

    @Test fun recognizedUserCommandDispatchesOnceOnlyAfterTheCompletedTurnSettles() {
        begin(command)
        tick()
        assertEquals(Phase.LISTENING, states.last().phase)
        assertTrue(requests.isEmpty())

        tracker.release()
        tick()
        assertEquals(Phase.THINKING, states.last().phase)
        assertTrue(requests.isEmpty())

        tracker.audio(speech = true)
        advance(1199)
        tick()
        assertEquals(Phase.SPEAKING, states.last().phase)
        assertTrue(requests.isEmpty())

        advance(1)
        tick()
        assertEquals(1, requests.size)
        assertEquals(SmsComposeAction("+15551234567", "Synthetic completed voice command"), requests.single().action)
        assertTrue(app.history.messages.isEmpty())
        assertTrue(failures.isEmpty())
        assertNull(field("session"))

        tick()
        advance(2000)
        tick()
        assertEquals(1, requests.size)
        assertEquals(1, settles)
    }

    @Test fun pendingBackendWorkPreventsQuietAudioFromCompletingAnSmsCommand() {
        begin(command)
        releaseAndReply()
        event(LiveProtocol.Event.DelegationStarted)
        advance(2000)
        tick()
        assertTrue(voice.busy)
        assertTrue(requests.isEmpty())

        event(LiveProtocol.Event.Backend("response.completed"))
        tick()
        assertEquals(1, requests.size)
        assertFalse(voice.busy)
    }

    @Test fun interruptionDiscardsAnOtherwiseRecognizedUserCommand() {
        begin(command)
        releaseAndReply()
        assertTrue(voice.interrupt())
        advance(2000)
        tick()
        assertTrue(requests.isEmpty())
        assertFalse(voice.busy)
        assertTrue(app.history.messages.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test fun failedExchangeNeverDispatchesItsRecognizedUserCommand() {
        begin(command)
        releaseAndReply()
        LiveVoice::class.java.getDeclaredMethod("failExchange", String::class.java)
            .apply { isAccessible = true }.invoke(voice, "Synthetic connection failure")
        advance(2000)
        tick()
        assertEquals(listOf("Synthetic connection failure"), failures)
        assertTrue(requests.isEmpty())
        assertFalse(voice.busy)
        assertTrue(app.history.messages.isEmpty())
    }

    @Test fun noReplyTimeoutIsFailureEvenWhenTheTrackerOtherwiseReachesIdle() {
        begin(command)
        tracker.release()
        advance(20_000)
        tick()
        assertEquals(listOf("No reply; please try again"), failures)
        assertTrue(requests.isEmpty())
        assertFalse(voice.busy)
        advance(2000)
        tick()
        assertTrue(requests.isEmpty())
    }

    @Test fun assistantOutputCannotAuthorizeASendWithoutAUserCommand() {
        begin("Explain how texting works", said = command)
        releaseAndReply()
        advance(1200)
        tick()
        assertTrue(requests.isEmpty())
        assertTrue(failures.isEmpty())
        assertFalse(voice.busy)
        assertEquals(listOf("Explain how texting works", command), app.history.messages.map { it.text })
    }

    @Test fun incompleteUserTranscriptCannotDispatchEvenAfterNormalCompletion() {
        begin("Text +15551234567:")
        releaseAndReply()
        advance(1200)
        tick()
        assertTrue(requests.isEmpty())
        assertTrue(failures.isEmpty())
        assertFalse(voice.busy)
    }
}
