package dev.r1ptt.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SpeechQueueTest {
    @Test fun playbackFailureWakesWaitingFetchBeforeLateFinishAndCannotReviveIt() {
        val queue = SpeechQueue(2, 10)
        val entering = CountDownLatch(1); val exited = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            entering.countDown()
            assertNull(queue.take())
            exited.countDown()
        }
        assertTrue(entering.await(1, TimeUnit.SECONDS))
        queue.close() // the playback-failure path closes fetch before finish() arrives
        assertTrue(exited.await(1, TimeUnit.SECONDS))
        queue.finish(); worker.join(1000)
        assertFalse(worker.isAlive); assertEquals(SpeechQueue.Admission.CLOSED, queue.offer("late"))
    }
    @Test fun finishDrainsAcceptedSentencesInOrderWithoutNeedingAnExtraQueueSlot() {
        val queue = SpeechQueue(2, 10)
        queue.offer("first"); queue.offer("last"); queue.finish()
        assertEquals("first", queue.take()); assertEquals("last", queue.take()); assertNull(queue.take())
        assertEquals(SpeechQueue.Admission.CLOSED, queue.offer("later"))
    }
    @Test fun queueAndSentenceSizeLimitsRejectBeforeAdmission() {
        val queue = SpeechQueue(1, 3)
        assertEquals(SpeechQueue.Admission.FULL, queue.offer("long"))
        assertEquals(SpeechQueue.Admission.ACCEPTED, queue.offer("ok"))
        assertEquals(SpeechQueue.Admission.FULL, queue.offer("x")); assertEquals("ok", queue.take())
    }
    @Test fun cancellationDropsQueuedSpeechAndWakesReader() {
        val queue = SpeechQueue(2, 10); queue.offer("old"); queue.close()
        assertNull(queue.take()); queue.finish(); assertNull(queue.take())
    }
}
