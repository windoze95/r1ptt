package dev.r1ptt.audio

import org.junit.Assert.*
import org.junit.Test

class CaptureAdmissionTest {
    @Test fun microphoneFaultSurvivesReleaseBeforePostedFailureCallbackRuns() {
        val gate = CaptureAdmission(); val token = gate.begin()
        assertTrue(gate.fail(token, "Microphone unavailable"))
        assertFalse(gate.accepts(token))
        assertEquals("Microphone unavailable", gate.close())
        assertFalse(gate.current(token)) // callback is stale, but release still owns the fault
    }
    @Test fun intentionalReleaseIgnoresLateMicrophoneStopError() {
        val gate = CaptureAdmission(); val token = gate.begin()
        assertNull(gate.close()); assertFalse(gate.fail(token, "late")); assertNull(gate.failure())
    }
    @Test fun overflowFaultCannotContaminateFreshCapture() {
        val gate = CaptureAdmission(); val old = gate.begin()
        gate.fail(old, "Audio pre-roll full"); gate.close()
        val fresh = gate.begin()
        assertNull(gate.failure()); assertTrue(gate.accepts(fresh))
        assertFalse(gate.fail(old, "old failure")); assertNull(gate.close())
    }
}
