package dev.r1ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.max

/** Bounded streamed PCM. Flush and each nonblocking write share ownership of the AudioTrack. */
class PcmPlayer(
    private val rate: Int,
    private val onStarted: (Long) -> Unit = {},
    private val onFailure: (Long) -> Unit = {},
) {
    private val queue = PlaybackQueue(rate * 2 * 8) // eight seconds, including the writer's packet
    private val gate = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L
    @Volatile private var stopped = false
    @Volatile private var firstSpeech: Pair<Long, Long>? = null // turn ID and head threshold after silence pad
    private var startedTurn = -1L
    private val worker = thread(name = "pcm-player") { loop() }

    fun play(pcm: ByteArray, turn: Long = 0, speech: Boolean = false): Boolean =
        !stopped && queue.offer(pcm, turn, speech)

    fun flush() = synchronized(gate) {
        queue.flush()
        firstSpeech = null
        startedTurn = -1L
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        framesWritten = head()
    }

    fun busy(): Boolean = synchronized(gate) {
        queue.busy() || (track != null && framesWritten - head() > rate / 50)
    }

    fun release() {
        stopped = true
        flush()
        worker.interrupt()
    }

    private fun loop() {
        var idleSince = SystemClock.elapsedRealtime()
        try {
            while (!stopped) {
                val chunk = queue.take(if (firstSpeech != null) 25 else 250)
                observeStarted()
                if (chunk == null) {
                    synchronized(gate) {
                        if (track != null && !busy() && SystemClock.elapsedRealtime() - idleSince > 3000) closeTrack()
                    }
                    continue
                }
                var off = 0
                var lastProgress = SystemClock.elapsedRealtime()
                try {
                    while (off < chunk.pcm.size && !stopped && queue.accepts(chunk)) {
                        val wrote = synchronized(gate) {
                            if (!queue.accepts(chunk)) return@synchronized -2
                            val t = track ?: openTrack()
                            // Small nonblocking writes let the main-thread flush win promptly.
                            val n = t.write(chunk.pcm, off, minOf(chunk.pcm.size - off, rate / 25 * 2), AudioTrack.WRITE_NON_BLOCKING)
                            if (n > 0) {
                                if (chunk.speech && chunk.turn != startedTurn && firstSpeech == null) {
                                    firstSpeech = chunk.turn to framesWritten
                                }
                                framesWritten += n / 2
                            }
                            n
                        }
                        if (wrote == -2) break
                        if (wrote < 0 || SystemClock.elapsedRealtime() - lastProgress >= 3000) {
                            throw IllegalStateException("Audio output unavailable")
                        }
                        if (wrote > 0) { off += wrote; lastProgress = SystemClock.elapsedRealtime() }
                        else Thread.sleep(5)
                        observeStarted()
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Exception) {
                    if (queue.accepts(chunk)) {
                        flush()
                        synchronized(gate) { closeTrack() }
                        onFailure(chunk.turn)
                    }
                } finally { queue.complete(chunk) }
                idleSince = SystemClock.elapsedRealtime()
            }
        } catch (_: InterruptedException) {
        } finally { synchronized(gate) { closeTrack() } }
    }

    private fun observeStarted() = synchronized(gate) {
        firstSpeech?.let { (turn, threshold) ->
            if (head() > threshold) {
                firstSpeech = null
                startedTurn = turn
                onStarted(turn) // AudioTrack head observation, not measured acoustic onset
            }
        }
    }

    private fun head() = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, rate / 2))
            .build()
        track = t
        t.play()
        // Preserve the existing amplifier warm-up pad; no acoustic latency claim.
        val pad = ByteArray(rate / 10 * 2)
        val n = t.write(pad, 0, pad.size, AudioTrack.WRITE_NON_BLOCKING)
        check(n == pad.size)
        framesWritten = n / 2L
        return t
    }

    private fun closeTrack() {
        val t = track ?: return
        track = null
        framesWritten = 0
        firstSpeech = null
        startedTurn = -1L
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}
