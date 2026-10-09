package dev.r1ptt.audio

/** Bounded PCM including the packet owned by the writer. Flush invalidates already-dequeued data. */
class PlaybackQueue(private val maxBytes: Int) {
    data class Packet(val pcm: ByteArray, val turn: Long, val speech: Boolean, val generation: Long)
    private val pending = ArrayDeque<Packet>()
    private var bytes = 0
    private var generation = 0L

    @Synchronized fun offer(pcm: ByteArray, turn: Long, speech: Boolean): Boolean {
        if (pcm.size > maxBytes - bytes) return false
        if (pcm.isNotEmpty()) {
            pending.addLast(Packet(pcm, turn, speech, generation))
            bytes += pcm.size
            (this as java.lang.Object).notifyAll()
        }
        return true
    }

    @Synchronized fun take(waitMs: Long): Packet? {
        if (pending.isEmpty()) (this as java.lang.Object).wait(waitMs)
        return if (pending.isEmpty()) null else pending.removeFirst()
    }

    @Synchronized fun accepts(packet: Packet) = packet.generation == generation
    @Synchronized fun complete(packet: Packet) {
        if (accepts(packet)) bytes -= packet.pcm.size
    }
    @Synchronized fun busy() = bytes > 0
    @Synchronized fun sizeBytes() = bytes
    @Synchronized fun flush() {
        generation++
        bytes = 0
        pending.clear()
        (this as java.lang.Object).notifyAll()
    }
}
