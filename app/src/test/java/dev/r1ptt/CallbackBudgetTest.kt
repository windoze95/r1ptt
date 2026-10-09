package dev.r1ptt

import org.junit.Assert.*
import org.junit.Test

class CallbackBudgetTest {
    @Test fun queuedMainCallbacksStayBoundedByBytesUntilTheyActuallyRun() {
        val b = CallbackBudget(8, 4)
        assertTrue(b.reserve(6)); assertFalse(b.reserve(3))
        b.release(6); assertTrue(b.reserve(8)); assertFalse(b.reserve(1))
    }
    @Test fun tinyProgressCallbacksCannotBypassEventCountBound() {
        val b = CallbackBudget(8, 2)
        assertTrue(b.reserve(0)); assertTrue(b.reserve(0)); assertFalse(b.reserve(0))
        b.release(0); assertTrue(b.reserve(0)); assertFalse(b.reserve(9))
    }
}
