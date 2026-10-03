package dev.r1ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Plays streamed 16-bit mono PCM as it arrives. Writing happens on its own thread so a full audio
 * buffer never stalls the network reader, [flush] silences it instantly (barge-in), and the audio
 * output is released after a few idle seconds so the codec can power down.
 */
class PcmPlayer(private val rate: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L
    @Volatile private var stopped = false
    @Volatile private var generation = 0
    private val worker = thread(name = "pcm-player") { loop() }

    fun play(pcm: ByteArray) {
        if (pcm.isNotEmpty()) queue.put(pcm)
    }

    /** Drops everything queued or buffered, immediately. */
    fun flush() {
        generation++
        queue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        framesWritten = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
    }

    /** Something is queued or still coming out of the speaker. */
    fun busy(): Boolean {
        if (queue.isNotEmpty()) return true
        val t = track ?: return false
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        return framesWritten - head > rate / 50 // more than 20 ms left
    }

    fun release() {
        stopped = true
        worker.interrupt()
    }

    private fun loop() {
        var idleSince = SystemClock.elapsedRealtime()
        try {
            while (!stopped) {
                val chunk = queue.poll(250, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // Give the audio hardware back after a few quiet seconds.
                    if (track != null && !busy() && SystemClock.elapsedRealtime() - idleSince > 3000) closeTrack()
                    continue
                }
                val gen = generation
                val t = track ?: openTrack()
                var off = 0
                while (off < chunk.size && gen == generation && !stopped) {
                    val w = t.write(chunk, off, chunk.size - off)
                    if (w < 0) break
                    off += w
                }
                if (gen == generation) framesWritten += chunk.size / 2
                idleSince = SystemClock.elapsedRealtime()
            }
        } catch (_: InterruptedException) {
        } finally {
            closeTrack()
        }
    }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, rate / 2)) // >= 250 ms
            .build()
        t.play()
        // 100 ms of silence first, so the amplifier waking up doesn't clip the first syllable.
        val pad = ByteArray(rate / 10 * 2)
        t.write(pad, 0, pad.size)
        framesWritten = pad.size / 2L
        track = t
        return t
    }

    private fun closeTrack() {
        val t = track ?: return
        track = null
        framesWritten = 0
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}
