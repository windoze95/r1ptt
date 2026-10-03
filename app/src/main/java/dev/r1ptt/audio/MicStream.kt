package dev.r1ptt.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * Raw 16-bit mono microphone audio in small chunks, for streaming to a live voice session while
 * the button is held. [onChunk] runs on the capture thread.
 */
class MicStream(
    private val rate: Int,
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // granted at setup; failure is handled like a busy mic
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate), // >= 0.5 s
            )
        } catch (e: Exception) {
            Log.w("r1ptt", "AudioRecord failed", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        running = true
        thread = Thread({ capture(rec) }, "mic-stream").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stops after the chunk in progress; when this returns, no more [onChunk] calls happen. */
    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun capture(rec: AudioRecord) {
        val buf = ByteArray(rate * 2 / 25) // 40 ms
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                onChunk(buf.copyOf(n - n % 2))
                onLevel(Recorder.rms(buf, n))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
