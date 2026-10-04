package dev.r1ptt

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.r1ptt.audio.Clip
import dev.r1ptt.audio.MicStream
import dev.r1ptt.audio.Wav
import dev.r1ptt.net.LiveTranscriber
import dev.r1ptt.net.TranscribeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Push-to-talk transcription through gpt-live-transcribe: the session connects at the press, audio
 * streams while the button is held and words come back as they're spoken ([onPartial], main
 * thread); release commits, and the final transcript lands about half a second later.
 *
 * The raw audio is also kept, so if the live session fails, [recording] lets the turn fall back to
 * transcribing the recording instead (gpt-transcribe).
 */
class SttStream(
    private val app: App,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val preRoll = ArrayList<ByteArray>() // captured before the hold was confirmed (lock)
    private val pcm = ByteArrayOutputStream() // everything captured, for the fallback (lock)
    private var streaming = false // chunks go straight to the transcriber (lock)
    private val partial = StringBuilder()
    private val result = CompletableDeferred<String>()
    @Volatile private var transcriber: LiveTranscriber? = null
    /** The final transcript is in, or the press was cancelled: no more provisional words. */
    @Volatile private var done = false
    @Volatile private var peakLevel = 0f
    private val mic = MicStream(TranscribeProtocol.SAMPLE_RATE, ::onChunk) { level ->
        if (level > peakLevel) peakLevel = level
        onLevel(level)
    }

    val peak: Float get() = peakLevel
    val durationMs: Long get() = synchronized(lock) { pcm.size() * 1000L / (TranscribeProtocol.SAMPLE_RATE * 2) }

    /** Opens the mic and starts connecting; false if the mic couldn't open. */
    fun start(): Boolean {
        if (!mic.start()) return false
        val cfg = app.store.value
        val t = LiveTranscriber(object : LiveTranscriber.Listener {
            override fun onDelta(text: String) {
                main.post {
                    if (done) return@post // a word that was still queued behind the final transcript
                    partial.append(text)
                    onPartial(partial.toString().trim())
                }
            }

            override fun onCompleted(text: String) {
                done = true
                result.complete(text.trim())
            }

            override fun onFailure(message: String) {
                Log.w(TAG, "live transcription failed: $message")
                result.completeExceptionally(RuntimeException(message))
            }
        })
        transcriber = t
        val update = TranscribeProtocol.sessionUpdate(
            cfg.sttLiveModel, cfg.sttDelay, listOfNotNull(cfg.sttLanguage.takeIf { it.isNotBlank() }),
        )
        connectWhenOnline(t, cfg.sttLiveUrl, cfg.keyFor(cfg.stt), update, SystemClock.elapsedRealtime() + NET_WAIT_MS)
        return true
    }

    /** The press is a hold: send what was captured so far and stream from here on. */
    fun hold() {
        val t = transcriber ?: return
        synchronized(lock) {
            preRoll.forEach { t.send(TranscribeProtocol.append(it)) }
            preRoll.clear()
            streaming = true
        }
    }

    /** Release: commit and wait for the final transcript. Null if the live session failed. */
    suspend fun finish(): String? {
        mic.stop()
        synchronized(lock) { streaming = false }
        transcriber?.send(TranscribeProtocol.COMMIT)
        val text = try {
            withTimeoutOrNull(FINAL_WAIT_MS) { result.await() }
        } catch (e: CancellationException) {
            throw e // the turn was interrupted; don't fall back to transcribing the recording
        } catch (e: Exception) {
            null
        }
        if (text == null) cancel() // the recording is kept for the fallback
        return text
    }

    /** What was captured, as a WAV clip, for transcribing the recording if the live session failed. */
    fun recording(dir: File): Clip {
        dir.mkdirs()
        val f = File(dir, "clip-${System.currentTimeMillis()}.wav")
        val bytes = synchronized(lock) { pcm.toByteArray() }
        Wav.write(f, bytes, TranscribeProtocol.SAMPLE_RATE)
        return Clip(f, "audio/wav", bytes.size * 1000L / (TranscribeProtocol.SAMPLE_RATE * 2), peakLevel)
    }

    /** The press was a tap, or the turn was interrupted: nothing to transcribe. */
    fun cancel() {
        done = true
        mic.stop()
        transcriber?.cancel()
        transcriber = null
        result.cancel()
    }

    private fun onChunk(chunk: ByteArray) {
        synchronized(lock) {
            if (pcm.size() < MAX_BYTES) pcm.write(chunk)
            if (streaming) transcriber?.send(TranscribeProtocol.append(chunk))
            else if (preRoll.size < MAX_PREROLL_CHUNKS) preRoll += chunk
        }
    }

    /** Wi-Fi may be coming back from an idle cut; audio queues in the transcriber meanwhile. */
    private fun connectWhenOnline(t: LiveTranscriber, url: String, key: String, update: String, deadline: Long) {
        if (transcriber !== t) return
        when {
            app.radio.isOnline() -> t.connect(url, key, update)
            SystemClock.elapsedRealtime() > deadline -> result.completeExceptionally(RuntimeException("No network"))
            else -> main.postDelayed({ connectWhenOnline(t, url, key, update, deadline) }, 200)
        }
    }

    private companion object {
        const val TAG = "r1ptt"
        const val NET_WAIT_MS = 30_000L
        /** The final transcript took ~0.5 s after the commit in testing; past this, fall back. */
        const val FINAL_WAIT_MS = 8_000L
        const val MAX_PREROLL_CHUNKS = 250
        /** Two minutes of audio. */
        val MAX_BYTES = max(1, TranscribeProtocol.SAMPLE_RATE * 2 * 120)
    }
}
