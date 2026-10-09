package dev.r1ptt.audio

/** A real capture fault survives the release barrier; errors after that barrier are stale. */
class CaptureAdmission {
    private var generation = 0L
    private var open = false
    private var fault: String? = null
    @Synchronized fun begin(): Long { generation++; open = true; fault = null; return generation }
    @Synchronized fun accepts(token: Long) = open && token == generation
    @Synchronized fun current(token: Long) = token == generation
    @Synchronized fun failure() = fault
    @Synchronized fun fail(token: Long, message: String): Boolean {
        if (!accepts(token)) return false
        fault = message
        open = false
        return true
    }
    @Synchronized fun close(): String? {
        open = false
        generation++
        return fault
    }
}
