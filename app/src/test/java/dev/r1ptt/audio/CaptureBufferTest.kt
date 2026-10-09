package dev.r1ptt.audio

import org.junit.Assert.*
import org.junit.Test

class CaptureBufferTest {
    @Test fun preRollIsSentOnlyAfterHoldAndEndFollowsAllAcceptedInput() {
        val events = mutableListOf<String>()
        val b = CaptureBuffer(100, 20) { events += String(it); true }
        b.append("first".toByteArray()); assertTrue(events.isEmpty())
        assertTrue(b.hold()); b.append("last".toByteArray())
        assertTrue(b.release { events += "COMMIT"; true })
        assertEquals(CaptureBuffer.Result.CLOSED, b.append("late".toByteArray()))
        assertEquals(listOf("first", "last", "COMMIT"), events)
        assertEquals("firstlast", String(b.bytes()))
    }
    @Test fun recordLimitRejectsEntireChunkAndClosesAdmission() {
        val b = CaptureBuffer(5, 5) { true }; b.hold()
        assertEquals(CaptureBuffer.Result.ACCEPTED, b.append(ByteArray(4)))
        assertEquals(CaptureBuffer.Result.FULL, b.append(ByteArray(2)))
        assertEquals(4, b.size()); assertEquals(CaptureBuffer.Result.CLOSED, b.append(ByteArray(1)))
    }
    @Test fun preRollIsBoundedIndependentlyOfRecording() {
        val b = CaptureBuffer(100, 4) { true }
        b.append(ByteArray(4)); assertEquals(CaptureBuffer.Result.FULL, b.append(ByteArray(1)))
        assertFalse(b.hold()); assertEquals(4, b.size())
    }
    @Test fun rejectedAppendCannotKeepCapturingAndCancelledPressSendsNothing() {
        var sends = 0
        val b = CaptureBuffer(100, 10) { sends++; false }
        b.append(ByteArray(2)); assertFalse(b.hold())
        assertEquals(CaptureBuffer.Result.CLOSED, b.append(ByteArray(2))); assertEquals(1, sends)
        val tap = CaptureBuffer(100, 10) { sends++; true }
        tap.append(ByteArray(2)); tap.stop(); assertFalse(tap.hold()); assertEquals(1, sends)
    }
    @Test fun releaseBeforePostedOverflowCallbackCannotCommitPartialRecording() {
        val b = CaptureBuffer(5, 5) { true }; b.hold()
        b.append(ByteArray(4)); assertEquals(CaptureBuffer.Result.FULL, b.append(ByteArray(2)))
        var committed = false
        assertFalse(b.release { committed = true; true })
        assertFalse(committed); assertTrue(b.failed()); assertEquals(4, b.size())
    }
    @Test fun microphoneFailureAndRejectedSendLatchBeforeReleaseCanRun() {
        val mic = CaptureBuffer(20, 10) { true }; mic.hold(); mic.append(ByteArray(2))
        assertTrue(mic.fail()); assertTrue(mic.failed())
        assertFalse(mic.release { throw AssertionError("Must not commit after microphone failure") })
        val send = CaptureBuffer(20, 10) { false }; send.hold()
        assertEquals(CaptureBuffer.Result.SEND_FAILED, send.append(ByteArray(2)))
        assertFalse(send.release { throw AssertionError("Must not commit after rejected send") })
    }
    @Test fun intentionalReleaseDoesNotBecomePartialBecauseRecordStopRaisesLateError() {
        val b = CaptureBuffer(20, 10) { true }; b.hold(); b.append(ByteArray(2))
        assertTrue(b.release { true }); assertFalse(b.fail()); assertFalse(b.failed())
    }
}
