package dev.r1ptt

import dev.r1ptt.net.ApiError
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class OutcomeStoreTest {
    private lateinit var app: App
    private var now = 1_000_000L
    private lateinit var store: OutcomeStore
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as App
        app.getSharedPreferences("action_outcomes", 0).edit().clear().commit()
        store = OutcomeStore(app) { now }
    }

    @Test fun boundedOutcomesSurviveRestartAndExpireAfterThreeDays() {
        repeat(55) { store.begin(OutcomeSource.TYPED); now++ }
        assertEquals(50, store.list().size)
        val reloaded = OutcomeStore(app) { now }
        assertEquals(store.list(), reloaded.list())
        now += 3 * 24 * 60 * 60_000L
        assertTrue(reloaded.list().isEmpty())
        assertEquals("[]", app.getSharedPreferences("action_outcomes", 0).getString("rows", ""))
    }

    @Test fun storedSchemaContainsOnlyCategoriesTimestampsRandomIdsAndNumericCodes() {
        val id = store.begin(OutcomeSource.VOICE)
        val error = ApiError(401, "synthetic body number URL token must not be stored")
        store.update(id, OutcomeStatus.FAILED, OutcomeStore.reason(error), error.code)
        val raw = app.getSharedPreferences("action_outcomes", 0).getString("rows", "")!!
        val row = JSONArray(raw).getJSONObject(0)
        assertEquals(setOf("id", "at", "source", "status", "reason", "code"), row.keys().asSequence().toSet())
        assertFalse(raw.contains(error.message!!))
        assertEquals(OutcomeReason.AUTHORIZATION, store.list().single().reason)
        assertEquals(401, store.list().single().code)
        assertEquals(OutcomeReason.TIMEOUT, OutcomeStore.reason(SocketTimeoutException("private")))
    }

    @Test fun nativeResultsCannotBeDowngradedByTurnCompletionCancellationOrLateHandoff() {
        val id = store.begin(OutcomeSource.TYPED)
        store.update(id, OutcomeStatus.SMS_SENT)
        for (status in listOf(OutcomeStatus.HANDOFF, OutcomeStatus.CANCELLED, OutcomeStatus.FAILED, OutcomeStatus.SMS_UNKNOWN)) {
            store.update(id, status)
            assertEquals(OutcomeStatus.SMS_SENT, store.list().single().status)
        }
        store.finishIfOpen(id, OutcomeStatus.COMPLETED)
        store.update(id, OutcomeStatus.SMS_DELIVERED)
        assertEquals(OutcomeStatus.SMS_DELIVERED, store.list().single().status)
    }

    @Test fun uncertainNativeResultsCanStillReceiveDefinitiveCarrierEvidence() {
        val id = store.begin(OutcomeSource.TYPED)
        store.update(id, OutcomeStatus.SMS_UNKNOWN)
        store.update(id, OutcomeStatus.SMS_FAILED, code = 2)
        assertEquals(OutcomeStatus.SMS_FAILED, store.list().single().status)
        assertEquals(2, store.list().single().code)
    }
}
