package dev.r1ptt

import android.util.Log
import dev.r1ptt.audio.Clip
import dev.r1ptt.audio.Earcon
import dev.r1ptt.audio.Recorder
import dev.r1ptt.audio.SentenceSplitter
import dev.r1ptt.audio.Speaker
import dev.r1ptt.audio.SpeechText
import dev.r1ptt.data.Msg
import dev.r1ptt.input.Gestures
import dev.r1ptt.net.CallRegistry
import dev.r1ptt.net.ChatClient
import dev.r1ptt.net.ChatRequest
import dev.r1ptt.net.Http
import dev.r1ptt.net.SttClient
import dev.r1ptt.net.TtsClient
import dev.r1ptt.net.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Phase {
    IDLE, LISTENING, DICTATING, TRANSCRIBING, THINKING, ANSWERING, SPEAKING, ERROR;

    val active: Boolean get() = this != IDLE && this != ERROR
}

data class TurnState(
    val phase: Phase = Phase.IDLE,
    /** Microphone level 0..1 while listening. */
    val level: Float = 0f,
    /** The reply streamed so far (until it lands in the history). */
    val reply: String = "",
    /** A short status line: errors, "New conversation", agent progress. */
    val note: String = "",
)

/** The launcher's text field while the keyboard is up: where dictation goes. */
interface DictationTarget {
    fun isActive(): Boolean
    fun insert(text: String)

    /** A tap while typing sends the field's text; false if it was empty. */
    fun sendTyped(): Boolean
    fun hideKeyboard()
}

/**
 * Everything one press of the button sets in motion:
 *
 *   hold → record → release → transcribe → (keyboard up?  insert into the text field)
 *                                          (otherwise    → ask the backend → stream reply → speak)
 *
 * A new press always interrupts whatever is going on. Gesture callbacks arrive on the main thread;
 * network work runs on a small pool and is cancelled with its turn.
 */
class TurnController(private val app: App) : Gestures.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pool = Executors.newCachedThreadPool()
    private val stt = SttClient()
    private val chat = ChatClient()
    private val tts = TtsClient { app.store.value }

    private val _state = MutableStateFlow(TurnState())
    val state: StateFlow<TurnState> = _state

    @Volatile var target: DictationTarget? = null

    val busy: Boolean get() = _state.value.phase.active || recorder != null

    @Volatile private var recorder: Recorder? = null
    @Volatile private var current: Turn? = null
    private var dictating = false
    private var interrupted = false
    private var noteJob: Job? = null

    private class Turn {
        var job: Job? = null
        var speaker: Speaker? = null
        val reply = StringBuffer()
        var replySaved = false
    }

    init {
        clipDir().listFiles()?.forEach { it.delete() } // leftovers from a crash
    }

    // ---- gestures (main thread) ----

    override fun onPress(atMs: Long) {
        app.screen.notePress()
        interrupted = stopActive()
        dictating = target?.isActive() == true
        app.radio.onActivity()
        app.screen.holdAwake()
        val r = Recorder(clipDir()) { level ->
            _state.update { if (it.phase == Phase.LISTENING || it.phase == Phase.DICTATING) it.copy(level = level) else it }
        }
        recorder = if (r.start()) r else null
    }

    override fun onHoldStart() {
        val cfg = app.store.value
        if (recorder == null) {
            fail("Microphone unavailable")
            return
        }
        noteJob?.cancel()
        _state.value = TurnState(phase = if (dictating) Phase.DICTATING else Phase.LISTENING)
        if (cfg.earcons) Earcon.listening()
        Http.warm(cfg.stt.baseUrl)
        if (!dictating) HomeActivity.bringToFront(app)
    }

    override fun onHoldEnd() {
        val r = recorder ?: return settle() // the mic never opened; onHoldStart already said so
        recorder = null
        val clip = r.stop()
        when {
            clip == null -> fail("Recording failed")
            clip.durationMs < MIN_CLIP_MS || clip.peak < SILENCE -> {
                discard(clip)
                note("Didn't hear anything")
            }
            else -> launch { turn -> transcribe(turn, clip) }
        }
        settle()
    }

    override fun onShortRelease() {
        recorder?.cancel()
        recorder = null
        settle()
    }

    override fun onTap() {
        val t = target
        when {
            interrupted -> interrupted = false // that press already stopped the reply
            t != null && t.isActive() -> if (!t.sendTyped()) t.hideKeyboard()
            app.screen.tapWasWake() -> {} // the tap that turned the screen on
            else -> app.screen.sleepNow()
        }
    }

    override fun onDoubleTap() {
        interrupted = false
        stopActive()
        app.history.newConversation()
        note("New conversation")
    }

    /** A message typed into the launcher's text field. */
    fun sendText(text: String) {
        stopActive()
        app.radio.onActivity()
        app.screen.holdAwake()
        launch { turn -> ask(turn, text) }
    }

    // ---- the turn ----

    private fun launch(block: suspend (Turn) -> Unit) {
        val turn = Turn()
        current = turn
        turn.job = scope.launch {
            try {
                block(turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "turn failed", e)
                if (current === turn) fail(friendly(e))
            } finally {
                turn.speaker?.stop()
                // Interrupted mid-reply: keep what arrived so the screen and the context match.
                if (!turn.replySaved && turn.reply.isNotBlank()) app.history.add(Msg.ASSISTANT, turn.reply.toString().trim() + " …")
                if (current === turn) {
                    current = null
                    settle()
                }
            }
        }
    }

    private suspend fun transcribe(turn: Turn, clip: Clip) {
        val cfg = app.store.value
        _state.value = TurnState(phase = Phase.TRANSCRIBING)
        val text = try {
            awaitNetwork()
            io { calls -> stt.transcribe(cfg, clip, calls) }
        } finally {
            discard(clip)
        }
        if (text.isBlank()) {
            note("Didn't catch that")
            return
        }
        val t = target
        if (dictating && t != null) {
            t.insert(text)
            idle()
            return
        }
        ask(turn, text)
    }

    private suspend fun ask(turn: Turn, text: String) {
        val cfg = app.store.value
        app.history.add(Msg.USER, text)
        _state.value = TurnState(phase = Phase.THINKING)
        awaitNetwork()
        val req = ChatRequest.build(cfg, app.history.messages, app.history.convId)
        val speaker = if (cfg.tts.enabled) {
            Speaker(tts::call, cfg.tts.sampleRate).also { turn.speaker = it; it.start() }
        } else null
        val splitter = SentenceSplitter()

        io { calls ->
            chat.stream(
                req, calls,
                onDelta = { d ->
                    if (current === turn) {
                        turn.reply.append(d)
                        _state.update { it.copy(phase = Phase.ANSWERING, reply = it.reply + d, note = "") }
                        speaker?.let { s -> splitter.feed(d).forEach { s.say(SpeechText.clean(it)) } }
                    }
                },
                onStatus = { s -> if (current === turn) _state.update { it.copy(note = s) } },
            )
        }

        val reply = turn.reply.toString().trim()
        if (reply.isEmpty()) {
            note("No reply")
            return
        }
        app.history.add(Msg.ASSISTANT, reply)
        turn.replySaved = true
        if (speaker == null) {
            idle()
            return
        }
        splitter.flush().forEach { speaker.say(SpeechText.clean(it)) }
        _state.update { it.copy(phase = Phase.SPEAKING, reply = "") }
        val error = suspendCancellableCoroutine<String?> { cont -> speaker.finish { e -> cont.resume(e) } }
        turn.speaker = null
        if (error != null) note(error) else idle()
    }

    private suspend fun awaitNetwork() {
        if (!app.radio.awaitOnline(NET_WAIT_MS)) throw IOException("No network")
    }

    /** Runs blocking network code on the pool; cancelling the coroutine aborts its HTTP calls. */
    private suspend fun <T> io(block: (CallRegistry) -> T): T = suspendCancellableCoroutine { cont ->
        val calls = CallRegistry()
        cont.invokeOnCancellation { calls.cancelAll() }
        pool.execute {
            try {
                cont.resume(block(calls))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    /** Stops whatever is in progress (reply, speech, recording). True if something was. */
    private fun stopActive(): Boolean {
        val t = current
        current = null
        val wasActive = t != null || _state.value.phase.active
        t?.speaker?.stop()
        t?.job?.cancel()
        recorder?.cancel()
        recorder = null
        if (wasActive) _state.value = TurnState()
        return wasActive
    }

    // ---- state helpers ----

    private fun idle() {
        _state.value = TurnState()
    }

    private fun note(msg: String) {
        _state.value = TurnState(note = msg)
        clearNoteLater(3000)
    }

    private fun fail(msg: String) {
        _state.value = TurnState(phase = Phase.ERROR, note = msg)
        if (app.store.value.earcons) Earcon.error()
        clearNoteLater(6000)
    }

    private fun clearNoteLater(ms: Long) {
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(ms)
            if (!_state.value.phase.active) _state.value = TurnState()
        }
    }

    /** Lets the CPU sleep again once nothing is recording or in flight. */
    private fun settle() {
        if (current == null && recorder == null) app.screen.release()
    }

    private fun clipDir() = File(app.cacheDir, "clips")

    private fun discard(clip: Clip) {
        if (app.store.value.saveClips) {
            runCatching {
                val keep = File(app.getExternalFilesDir("clips"), clip.file.name)
                clip.file.copyTo(keep, overwrite = true)
            }
        }
        clip.file.delete()
    }

    private companion object {
        const val TAG = "r1ptt"
        const val MIN_CLIP_MS = 400L
        /** Peak 100 ms RMS below this is silence (the button was held but nobody spoke). */
        const val SILENCE = 0.002f
        const val NET_WAIT_MS = 12_000L
    }
}
