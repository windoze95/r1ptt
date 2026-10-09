package dev.r1ptt.audio

/** Bounded sentence admission with an explicit terminal wake-up for a blocked fetch worker. */
class SpeechQueue(private val capacity: Int, private val maxChars: Int) {
    enum class Admission { ACCEPTED, FULL, CLOSED }
    private val pending = ArrayDeque<String>()
    private var finished = false
    private var closed = false
    @Synchronized fun offer(text: String): Admission {
        if (finished || closed) return Admission.CLOSED
        if (text.length > maxChars || pending.size >= capacity) return Admission.FULL
        pending.addLast(text)
        (this as java.lang.Object).notifyAll()
        return Admission.ACCEPTED
    }
    @Synchronized fun take(): String? {
        while (pending.isEmpty() && !finished && !closed) (this as java.lang.Object).wait()
        return if (closed || pending.isEmpty()) null else pending.removeFirst()
    }
    @Synchronized fun finish() { finished = true; (this as java.lang.Object).notifyAll() }
    @Synchronized fun close() {
        closed = true
        pending.clear()
        (this as java.lang.Object).notifyAll()
    }
}
