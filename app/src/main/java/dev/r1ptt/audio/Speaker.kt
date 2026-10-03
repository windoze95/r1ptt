package dev.r1ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import dev.r1ptt.net.ApiError
import dev.r1ptt.net.errorMessage
import dev.r1ptt.net.friendly
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Speaks a reply sentence by sentence while it is still streaming in. A fetch thread requests
 * speech for each sentence (16-bit PCM, raw or WAV) and hands chunks over as they arrive; a play
 * thread writes them to an AudioTrack. The next sentence downloads while the current one plays,
 * so speech starts after the first sentence rather than after the whole reply.
 */
class Speaker(
    /** Builds the text-to-speech call for one sentence. */
    private val newCall: (String) -> Call,
    /** Sample rate of raw PCM replies; a WAV reply's own header overrides it. */
    sampleRate: Int,
) {
    private val sentences = LinkedBlockingQueue<String>()
    private val chunks = LinkedBlockingQueue<ByteArray>()

    @Volatile private var rate = sampleRate
    @Volatile private var stopped = false
    @Volatile private var fetching = true
    @Volatile private var call: Call? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var error: String? = null
    @Volatile private var onDone: ((String?) -> Unit)? = null

    fun start() {
        thread(name = "tts-fetch") { fetchLoop() }
        thread(name = "tts-play") { playLoop() }
    }

    fun say(text: String) {
        if (text.isNotBlank() && !stopped) sentences.put(text)
    }

    /** No more sentences. [done] runs on the play thread once everything has been heard. */
    fun finish(done: (error: String?) -> Unit) {
        onDone = done
        sentences.put(END_TEXT)
    }

    /** Silences immediately; [finish]'s callback won't run. */
    fun stop() {
        if (stopped) return
        stopped = true
        sentences.clear()
        chunks.clear()
        sentences.offer(END_TEXT) // wake both threads
        chunks.offer(END_PCM)
        call?.cancel()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun fetchLoop() {
        var first = true
        try {
            while (!stopped && fetching) {
                val text = sentences.take()
                if (text === END_TEXT) break
                try {
                    fetch(text, first)
                    first = false
                } catch (e: IOException) {
                    if (!stopped) error = "Speech failed: ${friendly(e)}"
                    break // a failing voice service fails every sentence; the text is on screen anyway
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            chunks.put(END_PCM)
        }
    }

    private fun fetch(text: String, firstSentence: Boolean) {
        val c = newCall(text)
        call = c
        c.execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, errorMessage(resp.body?.string().orEmpty()))
            val ins = resp.body?.byteStream() ?: return
            val buf = ByteArray(8192)
            var head = true
            var carry = -1 // an odd trailing byte, completed by the next read
            while (!stopped) {
                val n = ins.read(buf)
                if (n < 0) break
                var data = buf.copyOf(n)
                if (head) {
                    head = false
                    if (firstSentence) Wav.sampleRate(data)?.let { rate = it } // before the first chunk is queued
                    data = Wav.stripHeader(data)
                }
                if (carry >= 0) {
                    data = byteArrayOf(carry.toByte()) + data
                    carry = -1
                }
                if (data.size % 2 == 1) {
                    carry = data.last().toInt() and 0xff
                    data = data.copyOf(data.size - 1)
                }
                if (data.isNotEmpty()) chunks.put(data)
            }
        }
    }

    private fun playLoop() {
        var t: AudioTrack? = null
        var frames = 0L
        var r = rate
        try {
            while (!stopped) {
                val chunk = chunks.take()
                if (chunk === END_PCM) break
                if (t == null) {
                    r = rate
                    t = newTrack(r).also { track = it }
                    t.play()
                    // 100 ms of silence first, so the amplifier waking up doesn't clip the first syllable.
                    val pad = ByteArray(r / 10 * 2)
                    t.write(pad, 0, pad.size)
                    frames += pad.size / 2
                }
                var off = 0
                while (off < chunk.size && !stopped) {
                    val w = t.write(chunk, off, chunk.size - off)
                    if (w < 0) throw IOException("AudioTrack write failed: $w")
                    off += w
                }
                frames += chunk.size / 2
            }
            if (t != null && !stopped) {
                // Let the buffered tail play out before stopping.
                val deadline = SystemClock.elapsedRealtime() + t.bufferSizeInFrames * 1000L / r + 1000
                while (!stopped && (t.playbackHeadPosition.toLong() and 0xffffffffL) < frames &&
                    SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(25)
                t.stop()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (error == null) error = "Playback failed"
            fetching = false
            call?.cancel()
        } finally {
            track = null
            t?.let { runCatching { it.release() } }
            if (!stopped) onDone?.invoke(error)
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, rate / 2)) // >= 250 ms
            .build()
    }

    private companion object {
        val END_TEXT = String(charArrayOf('\u0000'))
        val END_PCM = ByteArray(0)
    }
}
