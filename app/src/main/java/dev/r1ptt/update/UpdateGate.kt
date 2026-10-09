package dev.r1ptt.update

/** Main-thread admission fence: installing may start only while foreground and voice is idle. */
class UpdateGate {
    var installing = false
        private set
    fun begin(foreground: Boolean, voiceBusy: Boolean): Boolean {
        if (!foreground || voiceBusy || installing) return false
        installing = true
        return true
    }
    fun finish() { installing = false }
}
