package dev.r1ptt.net

import org.junit.Assert.*
import org.junit.Test

class SocketPumpTest {
    private class Wire : SocketPump.Wire {
        val sent = mutableListOf<String>()
        var bytes = 0L
        var reject = false
        override fun send(text: String): Boolean {
            if (reject) return false
            sent += text
            bytes += text.toByteArray().size
            return true
        }
        override fun queuedBytes() = bytes
    }
    private class Fixture {
        var now = 0L
        val failures = mutableListOf<String>()
        val markers = mutableListOf<String>()
        val wire = Wire()
        val pump = SocketPump({ now }, { failures += it }, { marker, _ -> markers += marker },
            maxBytes = 128, windowBytes = 64, reserveBytes = 8, timeoutMs = 100)
    }

    @Test fun waitsForAcknowledgementThenPreservesAudioEndOrderThroughBackpressure() {
        val f = Fixture(); f.pump.bind(f.wire)
        assertTrue(f.pump.offer("a".repeat(40)))
        assertTrue(f.pump.offer("b".repeat(40)))
        assertTrue(f.pump.offer("MUTE", control = true, marker = "input_end_sent"))
        assertTrue(f.wire.sent.isEmpty())
        f.now = 30; f.pump.acknowledge()
        assertEquals(listOf("a".repeat(40)), f.wire.sent)
        assertTrue(f.markers.isEmpty())
        f.wire.bytes = 0; f.now = 60; f.pump.poll()
        assertEquals(listOf("a".repeat(40), "b".repeat(40), "MUTE"), f.wire.sent)
        assertEquals(listOf("input_end_sent"), f.markers)
        assertEquals(60L, f.pump.stats().maxQueueMs)
    }
    @Test fun combinedAppAndWireBytesCannotExceedBudget() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.acknowledge()
        repeat(3) { assertTrue(f.pump.offer("a".repeat(40))) }
        assertEquals(120L, f.pump.stats().queuedBytes)
        assertFalse(f.pump.offer("b"))
        assertEquals(1, f.failures.size)
        assertEquals(0L, f.pump.stats().queuedBytes)
        assertFalse(f.pump.offer("MUTE", true))
    }
    @Test fun audioCannotConsumeReservedEndCommandAdmission() {
        val f = Fixture(); f.pump.bind(f.wire)
        repeat(3) { assertTrue(f.pump.offer("a".repeat(40))) }
        assertTrue(f.pump.offer("COMMIT", true))
        assertEquals(126L, f.pump.stats().queuedBytes)
    }
    @Test fun rejectedSendIsTerminalExactlyOnceAndNeverEmitsEndMarker() {
        val f = Fixture(); f.pump.bind(f.wire); f.wire.reject = true
        f.pump.offer("audio"); f.pump.offer("MUTE", true, "input_end_sent")
        assertFalse(f.pump.acknowledge())
        assertFalse(f.pump.poll()); assertFalse(f.pump.offer("later"))
        assertEquals(1, f.failures.size); assertTrue(f.markers.isEmpty())
        assertFalse(f.pump.ready); assertFalse(f.pump.needsWatch())
    }
    @Test fun handshakeTimeoutClearsUnacknowledgedAudio() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.offer("audio")
        f.now = 99; assertTrue(f.pump.poll())
        f.now = 100; assertFalse(f.pump.poll())
        assertTrue(f.wire.sent.isEmpty()); assertEquals(0L, f.pump.stats().queuedBytes)
        assertEquals(1, f.failures.size)
    }
    @Test fun fullWireThatNeverDrainsTimesOutWithoutRepeatedFailure() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.acknowledge()
        f.pump.offer("a".repeat(64))
        f.now = 100; assertFalse(f.pump.poll())
        f.now = 500; assertFalse(f.pump.poll())
        assertEquals(1, f.failures.size)
    }
    @Test fun observedSendProgressExtendsDeadlineAndIdleNeedsNoWatch() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.acknowledge()
        assertFalse(f.pump.needsWatch())
        f.pump.offer("a".repeat(40)); f.now = 90; f.wire.bytes = 20; assertTrue(f.pump.poll())
        f.now = 150; assertTrue(f.pump.poll())
        f.wire.bytes = 0; f.pump.poll(); assertFalse(f.pump.needsWatch())
        f.now = 1000; assertTrue(f.pump.poll()); assertTrue(f.failures.isEmpty())
    }
    @Test fun oversizedFrameFailsBeforeItCanBeQueuedOrSent() {
        val f = Fixture(); f.pump.bind(f.wire)
        assertFalse(f.pump.offer("a".repeat(65))); assertTrue(f.wire.sent.isEmpty())
        assertEquals(1, f.failures.size)
    }
    @Test fun intentionalCloseClearsQueueAndCannotBeRevivedByLateAcknowledgement() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.offer("audio")
        f.pump.close(); assertFalse(f.pump.acknowledge())
        assertFalse(f.pump.poll()); assertFalse(f.pump.needsWatch())
        assertTrue(f.failures.isEmpty()); assertTrue(f.wire.sent.isEmpty())
    }
    @Test fun smallContinuousWireProgressCannotKeepOldAppAudioQueuedForever() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.acknowledge()
        f.pump.offer("a".repeat(64)); f.pump.offer("b".repeat(40))
        f.now = 90; f.wire.bytes = 63; assertTrue(f.pump.poll())
        f.now = 100; f.wire.bytes = 62; assertFalse(f.pump.poll())
        assertEquals(1, f.failures.size); assertEquals(listOf("a".repeat(64)), f.wire.sent)
    }
    @Test fun checkedHandshakePrecedesQueuedAudioAndSharesTotalBudget() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.offer("audio")
        assertTrue(f.pump.handshake("START")); assertEquals(listOf("START"), f.wire.sent)
        f.pump.acknowledge(); assertEquals(listOf("START", "audio"), f.wire.sent)
        val full = Fixture(); full.pump.bind(full.wire)
        repeat(3) { full.pump.offer("a".repeat(40)) }
        assertFalse(full.pump.handshake("START-TOO-LARGE")); assertEquals(1, full.failures.size)
        val rejected = Fixture(); rejected.pump.bind(rejected.wire); rejected.wire.reject = true
        assertFalse(rejected.pump.handshake("START")); assertEquals(1, rejected.failures.size)
    }
    @Test fun lateAcknowledgementCannotBeatDeadlineWhileWatchdogIsDescheduled() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.offer("audio")
        f.now = 100
        assertFalse(f.pump.acknowledge()); assertTrue(f.wire.sent.isEmpty()); assertEquals(1, f.failures.size)
    }
    @Test fun laterOfferCannotDrainExpiredAudioBeforeWatchdogPollRuns() {
        val f = Fixture(); f.pump.bind(f.wire); f.pump.acknowledge()
        f.pump.offer("a".repeat(64)); f.pump.offer("b".repeat(40))
        f.now = 100; f.wire.bytes = 0
        assertFalse(f.pump.offer("new")); assertEquals(listOf("a".repeat(64)), f.wire.sent)
        assertEquals(1, f.failures.size)
    }
}
