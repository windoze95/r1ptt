package dev.r1ptt.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File

/** Streams 16-bit mono PCM through the platform AAC-LC encoder into an .m4a file. */
class AacWriter private constructor(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    private val rate: Int,
) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samplesIn = 0L
    private var written = 0

    fun write(pcm: ByteArray, len: Int) {
        var off = 0
        var waits = 0
        while (off < len) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i < 0) {
                drain(eos = false)
                check(++waits < 200) { "encoder stalled" }
                continue
            }
            val ib = codec.getInputBuffer(i) ?: error("no input buffer")
            ib.clear()
            val n = minOf(ib.remaining(), len - off)
            ib.put(pcm, off, n)
            codec.queueInputBuffer(i, 0, n, ptsUs(), 0)
            samplesIn += n / 2
            off += n
            drain(eos = false)
        }
    }

    /** Flushes and closes; true if a playable file was written. */
    fun finish(): Boolean = try {
        val i = codec.dequeueInputBuffer(50_000)
        if (i >= 0) codec.queueInputBuffer(i, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(eos = true)
        written > 0
    } catch (e: Exception) {
        Log.w("r1ptt", "AAC finish failed", e)
        false
    } finally {
        close()
    }

    /** Closes without caring about the output. */
    fun abort() = close()

    private fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxing) muxer.stop() }
        runCatching { muxer.release() }
        muxing = false
    }

    private fun ptsUs() = samplesIn * 1_000_000L / rate

    private fun drain(eos: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER ->
                    if (!eos || SystemClock.elapsedRealtime() > deadline) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                o >= 0 -> {
                    val ob = codec.getOutputBuffer(o)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (ob != null && !isConfig && info.size > 0 && muxing) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                        written++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        fun open(out: File, rate: Int, bitRate: Int): AacWriter? {
            var codec: MediaCodec? = null
            return try {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                AacWriter(codec, MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), rate)
            } catch (e: Exception) {
                Log.w("r1ptt", "AAC encoder unavailable, will send WAV", e)
                runCatching { codec?.release() }
                null
            }
        }
    }
}
