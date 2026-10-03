package dev.r1ptt

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.r1ptt.audio.MicStream
import dev.r1ptt.audio.PcmPlayer
import dev.r1ptt.audio.Recorder
import dev.r1ptt.data.Config
import dev.r1ptt.data.Msg
import dev.r1ptt.net.LiveProtocol
import dev.r1ptt.net.LiveSession

/**
 * Voice turns with the keyboard closed, as speech-to-speech through gpt-live-1, which hands real
 * thinking to the configured backend model (gpt-6.1-sol). Typed and dictated turns don't come here.
 *
 * Push-to-talk on a full-duplex model:
 * - the microphone streams only while the button is held (input is muted otherwise);
 * - reply audio plays only after release; audio arriving during a hold is held back, keeping just
 *   the last moment in case the answer began as the user finished speaking;
 * - a press stops a reply that is playing (barge-in);
 * - the session stays open [Config.live] idleCloseSec after an exchange for quick follow-ups, then
 *   closes, since it is billed per second while open.
 *
 * Confined to the main thread; network and audio callbacks are posted to it.
 */
class LiveVoice(
    private val app: App,
    private val publish: (TurnState) -> Unit,
    private val fail: (String) -> Unit,
    private val settled: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = LiveTracker(SystemClock::elapsedRealtime)
    private val player = PcmPlayer(LiveProtocol.SAMPLE_RATE)

    @Volatile private var session: LiveSession? = null
    private var mic: MicStream? = null
    private val micLock = Any()
    private var streaming = false // mic chunks go straight to the session (guarded by micLock)
    private val preRoll = ArrayList<ByteArray>() // captured before the hold was confirmed (micLock)
    private val held = ArrayDeque<Pair<Long, ByteArray>>() // reply audio that arrived during a hold
    private val heard = StringBuilder()
    private val said = StringBuilder()
    private var suppress = false // a press stopped the reply: drop the rest of it until the next turn
    private var active = false // an exchange is in progress
    @Volatile private var level = 0f
    private var waitingForNet = false
    private var firstAudioLogged = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (active) main.postDelayed(this, 200)
        }
    }
    /** Ends an idle session; never one mid-exchange (then it checks again later). */
    private val closer = Runnable { if (busy) scheduleClose() else closeSession() }

    val busy: Boolean get() = active || mic != null

    val capturing: Boolean get() = mic != null

    /** Stops a reply that is playing or being worked on. True if there was one. */
    fun interrupt(): Boolean {
        val audible = active && !tracker.holding
        if (audible) {
            player.flush()
            suppress = true
        }
        return audible
    }

    /** Starts capturing at the press, before we know it's a hold, so no word is lost. */
    fun startCapture(): Boolean {
        main.removeCallbacks(closer)
        synchronized(micLock) {
            preRoll.clear()
            streaming = false
        }
        val m = MicStream(LiveProtocol.SAMPLE_RATE, ::onMicChunk) { level = it }
        mic = if (m.start()) m else null
        return mic != null
    }

    /** The press is a hold: open (or reuse) the session, unmute, send what was captured so far. */
    fun holdStart() {
        Log.i(TAG, "live: hold, session ${if (session?.takeIf { !it.gone } != null) "reused" else "new"}, ${preRoll.size} chunks pre-rolled")
        flushExchange()
        tracker.hold()
        held.clear()
        active = true
        startTicker()
        val s = session?.takeIf { !it.gone } ?: openSession()
        s.send(LiveProtocol.UNMUTE)
        synchronized(micLock) {
            preRoll.forEach { s.send(LiveProtocol.append(it)) }
            preRoll.clear()
            streaming = true
        }
        publishState(LiveTracker.Phase.LISTENING)
    }

    /** Released after a hold: mute, and play any answer that began as the user finished. */
    fun holdEnd() {
        Log.i(TAG, "live: release")
        mic?.stop()
        mic = null
        synchronized(micLock) {
            streaming = false
            preRoll.clear()
        }
        session?.send(LiveProtocol.MUTE)
        tracker.release()
        suppress = false
        firstAudioLogged = false
        val cutoff = SystemClock.elapsedRealtime() - KEEP_TAIL_MS
        val tail = held.filter { it.first >= cutoff }.map { it.second }.dropWhile { !isSpeech(it) }
        held.clear()
        tail.forEach {
            player.play(it)
            tracker.audio(isSpeech(it))
        }
    }

    /** It was a tap: nothing was sent. If the press stopped a reply, that exchange is over. */
    fun shortRelease(stoppedReply: Boolean) {
        mic?.stop()
        mic = null
        synchronized(micLock) {
            streaming = false
            preRoll.clear()
        }
        if (stoppedReply) finishExchange()
        else if (!active) scheduleClose() // a warm session opened at the press shouldn't linger
    }

    /**
     * Opens a session ahead of time (screen on, or the moment of a press) so a hold doesn't wait for
     * the TLS and session handshake, which took 3.5 s on a cold start. It's billed per second while
     * open, so an idle warm session closes at screen-off or after idleCloseSec.
     */
    fun warm() {
        val cfg = app.store.value
        if (!cfg.liveVoice || !cfg.live.warm) return
        if (session?.takeIf { !it.gone } == null) {
            Log.i(TAG, "live: warming a session")
            openSession()
        }
        if (!busy) scheduleClose()
    }

    /** Screen off: nobody is about to talk, so don't pay for an idle session. */
    fun screenOff() {
        if (!busy) closeSession()
    }

    /** New conversation, or a typed turn: end the session so the next one starts from the history. */
    fun reset() {
        flushExchange()
        closeSession()
        player.flush()
        tracker.reset()
        held.clear()
        active = false
        suppress = false
    }

    // ---- internals ----

    private fun onMicChunk(pcm: ByteArray) {
        synchronized(micLock) {
            if (streaming) session?.send(LiveProtocol.append(pcm))
            else if (preRoll.size < MAX_PREROLL_CHUNKS) preRoll += pcm
        }
    }

    private fun openSession(): LiveSession {
        val cfg = app.store.value
        lateinit var s: LiveSession
        s = LiveSession(object : LiveSession.Listener {
            override fun onEvent(event: LiveProtocol.Event) {
                main.post { if (session === s) handle(event) }
            }

            override fun onFailure(message: String) {
                Log.w(TAG, "live: connection failed: $message")
                main.post {
                    if (session !== s) return@post
                    session = null
                    if (active) {
                        resetExchange()
                        fail(message)
                    }
                }
            }

            override fun onGone() {
                main.post { if (session === s) session = null }
            }
        })
        Log.i(TAG, "live: connecting to ${cfg.live.url} (${cfg.live.model} → ${cfg.live.backendModel})")
        session = s
        val start = LiveProtocol.start(
            cfg.live.model, instructions(cfg), cfg.live.voice, cfg.live.backendModel,
            cfg.live.reasoningEffort, cfg.live.webSearch,
        )
        val key = cfg.keyFor(cfg.liveEndpoint)
        connectWhenOnline(s, cfg.live.url, key, start, deadline = SystemClock.elapsedRealtime() + NET_WAIT_MS)
        return s
    }

    /** Wi-Fi may still be reconnecting after an idle cut; audio queues in the session meanwhile. */
    private fun connectWhenOnline(s: LiveSession, url: String, key: String, start: String, deadline: Long) {
        if (session !== s) return
        when {
            app.radio.isOnline() -> {
                waitingForNet = false
                s.connect(url, key, start)
            }
            SystemClock.elapsedRealtime() > deadline -> {
                Log.w(TAG, "live: no network after ${NET_WAIT_MS / 1000} s")
                waitingForNet = false
                session = null
                if (active) {
                    resetExchange()
                    fail("No network")
                }
            }
            else -> {
                waitingForNet = true // Wi-Fi is coming back from an idle cut; audio queues meanwhile
                main.postDelayed({ connectWhenOnline(s, url, key, start, deadline) }, 200)
            }
        }
    }

    private fun handle(event: LiveProtocol.Event) {
        when (event) {
            is LiveProtocol.Event.Audio -> {
                if (suppress) return
                if (tracker.holding) {
                    val now = SystemClock.elapsedRealtime()
                    held.addLast(now to event.pcm)
                    while (held.isNotEmpty() && held.first().first < now - KEEP_TAIL_MS) held.removeFirst()
                } else {
                    if (!firstAudioLogged && isSpeech(event.pcm)) { firstAudioLogged = true; Log.i(TAG, "live: first reply speech") }
                    player.play(event.pcm)
                    tracker.audio(isSpeech(event.pcm))
                }
            }
            is LiveProtocol.Event.Heard -> heard.append(event.delta)
            is LiveProtocol.Event.Said -> if (!suppress) said.append(event.delta)
            LiveProtocol.Event.DelegationStarted -> tracker.delegationStarted()
            is LiveProtocol.Event.Backend -> tracker.backend(event.finished, event.searching)
            is LiveProtocol.Event.Closed -> session = null
            is LiveProtocol.Event.Failed -> {
                Log.w(TAG, "live: server error: ${event.message}")
                closeSession()
                if (active) {
                    resetExchange()
                    fail(event.message)
                }
            }
            LiveProtocol.Event.Started -> {
                Log.i(TAG, "live: session started")
                tracker.restartWait()
            }
            LiveProtocol.Event.Other -> {}
        }
    }

    private fun tick() {
        if (!active) return
        val phase = tracker.phase(player.busy())
        when {
            !waitingForNet && tracker.noReply() -> {
                Log.w(TAG, "live: no reply")
                resetExchange()
                fail("No reply")
            }
            phase == LiveTracker.Phase.IDLE -> finishExchange()
            else -> publishState(phase)
        }
    }

    private fun publishState(phase: LiveTracker.Phase) {
        val cfg = app.store.value
        publish(
            when (phase) {
                LiveTracker.Phase.LISTENING -> TurnState(
                    phase = Phase.LISTENING, level = level, heard = heard.toString().trim(),
                    note = if (waitingForNet) CONNECTING else "",
                )
                LiveTracker.Phase.WAITING -> TurnState(
                    phase = Phase.THINKING, heard = heard.toString().trim(), reply = said.toString().trim(),
                    note = if (waitingForNet) CONNECTING else "",
                )
                LiveTracker.Phase.LOOKING_UP -> TurnState(
                    phase = Phase.THINKING, heard = heard.toString().trim(), reply = said.toString().trim(),
                    note = if (tracker.lookingUpWeb) "Searching the web…" else "Thinking (${cfg.live.backendModel})…",
                )
                LiveTracker.Phase.SPEAKING -> TurnState(phase = Phase.SPEAKING, heard = heard.toString().trim(), reply = said.toString().trim())
                LiveTracker.Phase.IDLE -> TurnState()
            }
        )
    }

    /** The exchange is over: save it, go idle, close the session after a while. */
    private fun finishExchange() {
        Log.i(TAG, "live: exchange done — heard ${heard.length} chars, said ${said.length} chars")
        flushExchange()
        tracker.reset()
        active = false
        publish(TurnState())
        scheduleClose()
        settled()
    }

    /** The exchange failed: drop it (the caller reports the error). */
    private fun resetExchange() {
        flushExchange()
        tracker.reset()
        player.flush()
        held.clear()
        active = false
        scheduleClose()
        settled()
    }

    private fun flushExchange() {
        val q = heard.toString().trim()
        val a = said.toString().trim()
        if (q.isNotEmpty()) app.history.add(Msg.USER, q)
        if (a.isNotEmpty()) app.history.add(Msg.ASSISTANT, a)
        heard.setLength(0)
        said.setLength(0)
    }

    private fun scheduleClose() {
        main.removeCallbacks(closer)
        main.postDelayed(closer, app.store.value.live.idleCloseSec.coerceIn(5, 600) * 1000L)
    }

    private fun closeSession() {
        main.removeCallbacks(closer)
        session?.close()
        session = null
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun instructions(cfg: Config): String {
        val recent = app.history.messages.takeLast(8).joinToString("\n") {
            (if (it.role == Msg.USER) "User: " else "You: ") + it.text.take(300)
        }.takeLast(2000)
        return buildString {
            append(cfg.systemPrompt)
            append(" The user holds a button while talking and lets go when finished.")
            if (recent.isNotBlank()) append("\n\nEarlier in this conversation:\n").append(recent)
        }
    }

    private fun isSpeech(pcm: ByteArray) = Recorder.rms(pcm, pcm.size) > SPEECH_RMS

    private companion object {
        /** Reply audio from this last part of a hold is kept: the answer may begin as the user finishes. */
        const val KEEP_TAIL_MS = 1500L
        const val SPEECH_RMS = 0.004f
        /** ~10 s of 40 ms chunks while waiting for the network. */
        const val MAX_PREROLL_CHUNKS = 250
        /** Wi-Fi coming back from an idle cut can take a while on the R1. */
        const val NET_WAIT_MS = 30_000L
        const val CONNECTING = "Connecting to Wi-Fi…"
        const val TAG = "r1ptt"
    }
}
