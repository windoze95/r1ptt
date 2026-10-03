package dev.r1ptt

/**
 * Works out what a live (speech-to-speech) exchange is doing from its events. gpt-live-1 has no
 * "reply finished" event, and it can go quiet for seconds mid-answer while the thinking model
 * works (web searches took 5–8 s in testing), so "over" means: not holding, no delegated work
 * outstanding, and no speech for [quietMs]. Pure logic with an injected clock, for unit tests.
 */
class LiveTracker(
    private val clock: () -> Long,
    private val quietMs: Long = 1200,
    private val noReplyMs: Long = 20_000,
) {
    enum class Phase { IDLE, LISTENING, WAITING, LOOKING_UP, SPEAKING }

    var holding = false
        private set
    private var releasedAt = -1L
    private var lastSpeechAt = -1L
    private var gotReply = false
    private var delegations = 0
    private var searching = false

    fun hold() {
        holding = true
    }

    fun release() {
        holding = false
        releasedAt = clock()
        gotReply = false
    }

    /** Output audio arrived (and is being played, i.e. not held back); [speech] = it isn't silence. */
    fun audio(speech: Boolean) {
        if (!speech || holding) return
        lastSpeechAt = clock()
        gotReply = true
    }

    fun delegationStarted() {
        delegations++
    }

    fun backend(finished: Boolean, isSearch: Boolean) {
        if (isSearch) searching = true
        if (finished) {
            delegations = (delegations - 1).coerceAtLeast(0)
            if (delegations == 0) searching = false
        }
    }

    /** Forget the exchange (a tap stopped it, or the session ended). */
    fun reset() {
        holding = false
        releasedAt = -1
        lastSpeechAt = -1
        gotReply = false
        delegations = 0
        searching = false
    }

    val lookingUpWeb: Boolean get() = searching

    fun phase(playing: Boolean): Phase {
        val now = clock()
        return when {
            holding -> Phase.LISTENING
            releasedAt < 0 -> Phase.IDLE
            playing || (lastSpeechAt >= releasedAt && now - lastSpeechAt < quietMs) -> Phase.SPEAKING
            delegations > 0 -> Phase.LOOKING_UP
            !gotReply && now - releasedAt < noReplyMs -> Phase.WAITING
            else -> Phase.IDLE
        }
    }

    /** Released, nothing came back at all, and nothing is being worked on. */
    fun noReply(): Boolean = !holding && releasedAt >= 0 && !gotReply && delegations == 0 && clock() - releasedAt >= noReplyMs
}
