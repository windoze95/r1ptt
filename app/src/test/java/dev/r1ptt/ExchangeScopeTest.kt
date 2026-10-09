package dev.r1ptt

import org.junit.Assert.*
import org.junit.Test

class ExchangeScopeTest {
    @Test fun queuedEventsFromInterruptedConnectionCannotEnterFollowingExchange() {
        val scope = ExchangeScope(); val old = scope.begin(1)
        scope.end(); scope.begin(2)
        assertFalse(scope.accepts(old)); assertFalse(scope.accepts(scope.snapshot(1)))
        assertTrue(scope.accepts(scope.snapshot(2)))
    }
    @Test fun callbacksQueuedBeforeNormalWarmReuseAreAlsoFencedByGeneration() {
        val scope = ExchangeScope(); val old = scope.begin(1)
        scope.end(); val fresh = scope.begin(1)
        assertFalse(scope.accepts(old)); assertTrue(scope.accepts(fresh))
    }
    @Test fun idleAndResetCannotAcceptTranscriptAudioOrBackendUpdates() {
        val scope = ExchangeScope(); val warm = scope.snapshot(1)
        assertFalse(scope.accepts(warm)); val active = scope.begin(1)
        scope.end(); assertFalse(scope.accepts(active)); assertFalse(scope.accepts(scope.snapshot(1)))
    }
}
