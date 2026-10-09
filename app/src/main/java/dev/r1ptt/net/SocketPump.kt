package dev.r1ptt.net

/** Ordered, bounded admission and progress tracking. No Android, timers, or socket implementation. */
class SocketPump(
    private val clock: () -> Long,
    private val failed: (String) -> Unit,
    private val sent: (String, Stats) -> Unit = { _, _ -> },
    private val maxBytes: Long = 2 * 1024 * 1024,
    private val windowBytes: Long = 256 * 1024,
    private val reserveBytes: Long = 4096,
    private val timeoutMs: Long = 30_000,
) {
    interface Wire {
        fun send(text: String): Boolean
        fun queuedBytes(): Long
    }
    data class Stats(val queuedBytes: Long, val maxQueueMs: Long)
    private data class Entry(val text: String, val bytes: Long, val at: Long, val marker: String)
    private val pending = ArrayDeque<Entry>()
    private var bytes = 0L
    private var wire: Wire? = null
    private var connectingAt: Long? = null
    private var progressAt = 0L
    private var previousBacklog = 0L
    private var maxQueueMs = 0L
    @Volatile var ready = false
        private set
    @Volatile var terminal = false
        private set

    @Synchronized fun bind(wire: Wire) {
        if (terminal || this.wire != null) return
        this.wire = wire
        connectingAt = clock()
        progressAt = clock()
    }

    /** Handshake is deliberately separate from a TCP/WebSocket connection. */
    @Synchronized fun acknowledge(): Boolean {
        if (terminal || wire == null) return false
        if (!ready) { ready = true; progressAt = clock() }
        drain()
        return !terminal
    }

    /** The handshake precedes queued PCM but shares its byte budget and checked-send policy. */
    @Synchronized fun handshake(text: String): Boolean {
        if (terminal || ready) return false
        val target = wire ?: return false
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (size > windowBytes || bytes + target.queuedBytes() + size > maxBytes) return reject("Voice session request too large")
        if (!target.send(text)) return reject("Voice session request failed")
        previousBacklog = target.queuedBytes()
        return true
    }

    @Synchronized fun offer(text: String, control: Boolean = false, marker: String = ""): Boolean {
        if (terminal) return false
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        val limit = maxBytes - if (control) 0 else reserveBytes
        val backlog = wire?.queuedBytes()?.coerceAtLeast(0) ?: 0
        if (size > windowBytes || bytes + backlog + size > limit) return reject("Audio queue full; input may be partial")
        if (pending.isEmpty() && backlog == 0L) progressAt = clock()
        pending.addLast(Entry(text, size, clock(), marker))
        bytes += size
        drain()
        return !terminal
    }

    @Synchronized fun poll(): Boolean {
        if (terminal) return false
        val now = clock()
        if (pending.firstOrNull()?.let { now - it.at >= timeoutMs } == true) {
            return reject("Queued voice input expired; input may be partial")
        }
        if (!ready && connectingAt?.let { now - it >= timeoutMs } == true) {
            return reject("Voice connection timed out")
        }
        val backlog = wire?.queuedBytes()?.coerceAtLeast(0) ?: 0
        if (backlog < previousBacklog) progressAt = now
        if (ready && (pending.isNotEmpty() || backlog > 0) && now - progressAt >= timeoutMs) {
            return reject("Voice sending stalled; input may be partial")
        }
        previousBacklog = backlog
        drain()
        return !terminal
    }

    @Synchronized fun needsWatch(): Boolean = !terminal && wire != null &&
        (!ready || pending.isNotEmpty() || wire!!.queuedBytes() > 0)

    @Synchronized fun stats(): Stats = Stats(bytes + (wire?.queuedBytes()?.coerceAtLeast(0) ?: 0), maxQueueMs)

    @Synchronized fun close() {
        terminal = true
        ready = false
        pending.clear()
        bytes = 0
        wire = null
    }

    private fun drain() {
        val target = wire ?: return
        if (!ready || terminal) return
        while (pending.isNotEmpty()) {
            val entry = pending.first()
            if (target.queuedBytes() > windowBytes - entry.bytes) break
            if (!target.send(entry.text)) { reject("Voice send failed; input may be partial"); return }
            pending.removeFirst()
            bytes -= entry.bytes
            maxQueueMs = maxOf(maxQueueMs, (clock() - entry.at).coerceAtLeast(0))
            progressAt = clock()
            if (entry.marker.isNotEmpty()) sent(entry.marker, stats())
        }
        previousBacklog = target.queuedBytes().coerceAtLeast(0)
    }

    private fun reject(message: String): Boolean {
        close()
        failed(message)
        return false
    }
}
