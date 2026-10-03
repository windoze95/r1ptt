package dev.r1ptt.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    /** Writes 16-bit mono PCM as a WAV file. */
    fun write(out: File, pcm: ByteArray, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        out.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    /**
     * The audio after a RIFF/WAVE header, so the header isn't played as a click. Returns [first]
     * unchanged when it doesn't start with one (raw PCM).
     */
    fun stripHeader(first: ByteArray): ByteArray =
        chunk(first, "data")?.let { first.copyOfRange(it, first.size) } ?: first

    /** The sample rate declared in a WAV header at the start of [first], or null for raw PCM. */
    fun sampleRate(first: ByteArray): Int? =
        chunk(first, "fmt ")?.let { at -> if (at + 8 <= first.size) le(first).getInt(at + 4) else null }

    /** Offset of the body of the RIFF chunk called [id], or null. */
    private fun chunk(b: ByteArray, id: String): Int? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val bb = le(b)
        var p = 12
        while (p + 8 <= b.size) {
            val len = bb.getInt(p + 4)
            if (String(b, p, 4, Charsets.US_ASCII) == id) return p + 8
            if (len < 0) break // streaming servers may write 0xFFFFFFFF lengths
            p += 8 + len + (len and 1)
        }
        return null
    }

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
}
