package dev.r1ptt.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** One captured utterance, ready to upload. [peak] is the loudest 100 ms RMS level, 0..1. */
class Clip(val file: File, val mime: String, val durationMs: Long, val peak: Float)

/**
 * Microphone capture for one press. It starts the instant the button goes down, before we know
 * whether it's a hold, and encodes AAC while recording, so on release there is nothing left to do
 * but upload. 16 kHz mono is what speech models work at anyway, and ~32 kbit/s AAC is a sixth the
 * size of WAV: fewer bytes, less radio time. WAV is the fallback if the encoder misbehaves.
 */
class Recorder(private val dir: File, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    @Volatile private var result: Clip? = null
    private var thread: Thread? = null

    /** Opens the mic; false if it couldn't (no permission, or busy). */
    @SuppressLint("MissingPermission") // granted at setup; failure is handled like any busy mic
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // no AGC or noise suppression: what STT wants
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE), // >= 0.5 s
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording failed", e)
            rec.release()
            return false
        }
        dir.mkdirs()
        running = true
        thread = Thread({ capture(rec) }, "recorder").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stops and returns the clip, or null if nothing usable was captured. */
    fun stop(): Clip? {
        running = false
        thread?.join(3000)
        thread = null
        return result
    }

    /** Stops and throws the audio away (it was a tap, not a hold). */
    fun cancel() {
        stop()?.file?.delete()
        result = null
    }

    private fun capture(rec: AudioRecord) {
        val stamp = System.currentTimeMillis()
        val m4a = File(dir, "clip-$stamp.m4a")
        var aac = AacWriter.open(m4a, RATE, BITRATE)
        val pcm = ByteArrayOutputStream(RATE * 2 * 8)
        val buf = ByteArray(RATE * 2 / 10) // 100 ms
        var peak = 0f
        try {
            while (running && pcm.size() < MAX_BYTES) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                pcm.write(buf, 0, n)
                aac = aac?.let { w ->
                    try {
                        w.write(buf, n)
                        w
                    } catch (e: Exception) {
                        Log.w(TAG, "AAC encoder failed, falling back to WAV", e)
                        w.abort()
                        null
                    }
                }
                val level = rms(buf, n)
                if (level > peak) peak = level
                onLevel(level)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val ms = pcm.size() * 1000L / (RATE * 2)
        result = if (aac?.finish() == true) {
            Clip(m4a, "audio/mp4", ms, peak)
        } else {
            m4a.delete()
            val wav = File(dir, "clip-$stamp.wav")
            Wav.write(wav, pcm.toByteArray(), RATE)
            Clip(wav, "audio/wav", ms, peak)
        }
    }

    companion object {
        private const val TAG = "r1ptt"
        const val RATE = 16_000
        const val BITRATE = 32_000
        /** Two minutes, then the clip is cut off. */
        private const val MAX_BYTES = RATE * 2 * 120

        fun rms(b: ByteArray, len: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < len) {
                val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val n = len / 2
            return if (n == 0) 0f else (sqrt(sum / n) / 32768.0).toFloat()
        }
    }
}
