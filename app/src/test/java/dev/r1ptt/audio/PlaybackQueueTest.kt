package dev.r1ptt.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun dequeueKeepsInFlightBytesInBudgetUntilWriteCompletes() {
        val q = PlaybackQueue(8); assertTrue(q.offer(ByteArray(6), 1, true))
        val writing = q.take(1)!!
        assertTrue(q.busy()); assertFalse(q.offer(ByteArray(3), 1, true))
        q.complete(writing); assertFalse(q.busy()); assertTrue(q.offer(ByteArray(8), 1, true))
    }
    @Test fun flushInvalidatesAlreadyDequeuedAudioWithoutDroppingNextTurn() {
        val q = PlaybackQueue(8); q.offer(ByteArray(8), 1, true)
        val old = q.take(1)!!; q.flush()
        assertFalse(q.accepts(old)); assertTrue(q.offer(ByteArray(8), 2, true))
        q.complete(old); assertEquals(8, q.sizeBytes())
        val fresh = q.take(1)!!; assertTrue(q.accepts(fresh)); assertEquals(2L, fresh.turn)
        q.complete(fresh); assertEquals(0, q.sizeBytes())
    }
    @Test fun oversizedReplyIsRejectedWithoutChangingQueuedAudio() {
        val q = PlaybackQueue(8); q.offer(ByteArray(2), 1, false)
        assertFalse(q.offer(ByteArray(9), 1, true)); assertEquals(2, q.sizeBytes())
    }
}
