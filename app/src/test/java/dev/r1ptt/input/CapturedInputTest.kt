package dev.r1ptt.input

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturedInputTest {
    @Test fun leavingOrReplacingADraftNeverFallsThroughToChat() {
        val draft = Any()
        val capture = CapturedInput(draft)
        assertEquals(InputRoute.DRAFT, capture.route(draft, true))
        assertEquals(InputRoute.DISCARDED, capture.route(draft, false))
        assertEquals(InputRoute.DISCARDED, capture.route(null, false))
        assertEquals(InputRoute.DISCARDED, capture.route(Any(), true))
    }
    @Test fun anExistingVoiceTurnCannotBeRedirectedIntoANewDraft() {
        val capture = CapturedInput<Any>(null)
        assertEquals(InputRoute.CHAT, capture.route(Any(), true))
    }
}
