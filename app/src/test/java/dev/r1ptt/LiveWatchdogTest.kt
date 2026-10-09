package dev.r1ptt

import org.junit.Assert.*
import org.junit.Test

class LiveWatchdogTest {
    @Test fun stuckDelegationAndSilenceCannotExtendWholeExchangeDeadline() {
        var now = 0L; val t = LiveTracker({ now }, maxExchangeMs = 100)
        t.hold(); now = 10; t.release(); t.delegationStarted()
        now = 99; assertFalse(t.expired()); assertFalse(t.noReply())
        now = 100; assertTrue(t.expired())
        t.reset(); assertFalse(t.expired())
    }
    @Test fun missingReleaseAndEndlessSpeechAreAlsoBoundedAndNextTurnGetsFreshDeadline() {
        var now = 1L; val t = LiveTracker({ now }, maxExchangeMs = 100)
        t.hold(); now = 101; assertTrue(t.expired())
        t.reset(); t.hold(); t.release(); t.audio(true)
        now = 200; t.audio(true); assertFalse(t.expired())
        now = 201; assertTrue(t.expired())
    }
}
