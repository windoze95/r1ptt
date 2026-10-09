package dev.r1ptt

/** Bounds the network-to-main handoff before Android's message queue owns its payloads. */
class CallbackBudget(private val maxBytes: Int, private val maxEvents: Int) {
    private var bytes = 0
    private var events = 0
    @Synchronized fun reserve(size: Int): Boolean {
        if (size < 0 || size > maxBytes - bytes || events >= maxEvents) return false
        bytes += size; events++
        return true
    }
    @Synchronized fun release(size: Int) { bytes -= size; events-- }
}
