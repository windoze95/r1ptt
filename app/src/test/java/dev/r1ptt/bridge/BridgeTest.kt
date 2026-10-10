package dev.r1ptt.bridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.PowerManager
import org.robolectric.RuntimeEnvironment
import dev.r1ptt.data.BridgeConfig
import dev.r1ptt.data.Config as AppConfig
import dev.r1ptt.data.ConfigJson
import dev.r1ptt.messages.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now get() = System.currentTimeMillis() / 1000
    private fun id() = UUID.randomUUID().toString()
    @Before fun reset() { context.deleteDatabase("messages.db") }
    private fun command(id: String = id()) = JSONObject().put("version", 1).put("id", id).put("lane", "owner")
        .put("conversation", id()).put("peer_id", id()).put("text", "Text Sam hello").put("mode", "send")
        .put("sim", 1).put("expires", now + 300)
    private fun frozen(store: SmsStore): Pair<String, SmsRecord> {
        val journal = BridgeJournal(store)
        val key = journal.enqueue(command(), null, "enrollment")
        journal.update(key, "ready", "result")
        val record = SmsRecord.outgoing("+14155550123", "Hello!", 1, 1, System.currentTimeMillis())
        val digest = BridgePolicy.digest(BridgeController.frozen(record))
        journal.freeze(key, record, digest)
        journal.grant(key, digest, now + 30)
        return key to record
    }
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun selectingTheSameIncomingMessageAcrossRestartsAndExpiryDoesNotCreateAnotherJob() {
        val peer = id()
        val selected = command(BridgeController.selectedId("device", "incoming-message"))
            .put("lane", "selected").put("mode", "explain").put("peer_id", peer)
        repeat(10) {
            SmsStore(context).use { store ->
                val journal = BridgeJournal(store)
                assertEquals(selected.getString("id"), journal.enqueueSelected(selected.put("expires", now + 300 + it), "enrollment"))
                assertEquals(1, journal.jobs().size)
                rejected { journal.enqueueSelected(command().put("lane", "selected").put("peer_id", peer), "enrollment") }
            }
        }
        SmsStore(context).use { store ->
            val journal = BridgeJournal(store)
            journal.prune(now + 86401)
            assertNull(journal.get(selected.getString("id"))?.payload)
            assertEquals(selected.getString("id"), journal.enqueueSelected(selected, "enrollment"))
            assertEquals("expired", journal.get(selected.getString("id"))?.state)
            assertEquals(1, journal.jobs().size)
        }
    }

    @Test fun tenDatabaseReopensCannotRepeatTheSameNativeHandoffEvenWithoutCallbacks() {
        val pair = SmsStore(context).use { store -> frozen(store).also { store.outgoing(it.second, null, it.first) } }
        repeat(10) {
            SmsStore(context).use { store ->
                val (key, record) = pair
                assertEquals("handoff", BridgeJournal(store).get(key)?.state)
                rejected { store.outgoing(record, null, key) }
                assertEquals(listOf(record), store.conversation(record.peer))
            }
        }
    }
    @Test fun cancellationWinsOverLateReadyAndNativeClaim() {
        SmsStore(context).use { store ->
            val (key, record) = frozen(store); val journal = BridgeJournal(store)
            journal.cancel(key); journal.update(key, "ready", "late")
            rejected { store.outgoing(record, null, key) }
            assertEquals("stopping", journal.get(key)?.state)
            assertNull(store.record(record.id))
        }
    }
    @Test fun changingFrozenBodySimCallbackTokenOrRecipientFailsBeforeNativeInsert() {
        SmsStore(context).use { store ->
            val (key, record) = frozen(store)
            for (changed in listOf(record.copy(body = "changed"), record.copy(subscriptionId = 2),
                record.copy(token = id()), record.copy(peer = "+14155550124"), record.copy(parts = List(2) { SmsPart() }))) {
                rejected { store.outgoing(changed, null, key) }
                assertNull(store.record(record.id))
                assertEquals("frozen", BridgeJournal(store).get(key)?.state)
            }
        }
    }
    @Test fun failedMessageInsertRollsBackTheAuthorizationClaim() {
        SmsStore(context).use { store ->
            val (key, record) = frozen(store)
            store.insert(record)
            rejected { store.outgoing(record, null, key) }
            assertEquals("frozen", BridgeJournal(store).get(key)?.state)
        }
    }
    @Test fun expiredLeaseAndLocalBlockBothPreventHandoff() {
        SmsStore(context).use { store ->
            val (key, record) = frozen(store); val journal = BridgeJournal(store)
            journal.grant(key, journal.get(key)!!.digest!!, now - 1)
            rejected { store.outgoing(record, null, key) }
            journal.grant(key, journal.get(key)!!.digest!!, now + 20)
            journal.block(record.peer)
            rejected { store.outgoing(record, null, key) }
            assertNull(store.record(record.id))
        }
    }
    @Test fun pruningKeepsReplayIdAndDoesNotDeleteNativeMessages() {
        SmsStore(context).use { store ->
            val (key, record) = frozen(store); val journal = BridgeJournal(store)
            store.outgoing(record, null, key)
            journal.prune(now + BridgePolicy.RETENTION_SECONDS + 1)
            assertEquals("handoff", journal.get(key)?.state)
            assertNull(journal.get(key)?.payload); assertNull(journal.get(key)?.record)
            assertEquals(record, store.record(record.id))
            rejected { journal.enqueue(command(key), null, "enrollment") }
        }
    }
    @Test fun savedDraftIsUnsentAndDoesNotOverwriteManualDraft() {
        SmsStore(context).use { store ->
            val manual = SmsDraft("+14155550123", "Manual"); store.saveDraft(manual)
            val journal = BridgeJournal(store)
            val key = journal.enqueue(command().put("mode", "draft"), null, "enrollment")
            journal.update(key, "ready")
            val generated = SmsDraft("+14155550124", "Prepared")
            journal.draft(key, generated)
            assertEquals(manual, store.draft())
            assertEquals(listOf(key to generated), store.replyRequests())
            assertTrue(store.threads().isEmpty())
            assertEquals("draft", journal.get(key)?.state)
        }
    }
    @Test fun upgradingVersionTwoKeepsDraftRecipientsAndNativeRecords() {
        val record = SmsRecord.outgoing("+14155550123", "Existing", 1, 1, System.currentTimeMillis())
        SmsStore(context).use { store ->
            store.saveDraft(SmsDraft(record.peer, "Manual")); store.addRecipient("Sam", record.peer); store.insert(record)
            store.writableDatabase.execSQL("DROP TABLE bridge_jobs"); store.writableDatabase.execSQL("DROP TABLE bridge_peers")
            store.writableDatabase.version = 2
        }
        SmsStore(context).use { store ->
            assertEquals(record, store.record(record.id)); assertEquals("Manual", store.draft().body)
            assertEquals("Sam", store.recipients().single().name)
            assertTrue(BridgeJournal(store).jobs().isEmpty())
        }
    }
    @Test fun pendingCommandsStayVisibleEvenAfterManyTerminalRows() {
        SmsStore(context).use { store ->
            val journal = BridgeJournal(store); val key = journal.enqueue(command(), null, "enrollment")
            repeat(120) { val ended = journal.enqueue(command(), null, "enrollment"); journal.update(ended, "failed") }
            assertTrue(journal.jobs().any { it.id == key })
        }
    }
    @Test fun unicodeDigestMatchesServerContract() {
        val data = JSONObject().put("recipient", "+14155550123").put("body", "Hi — café 😀").put("sim", 1).put("parts", 1)
            .put("attempt", "00000000-0000-4000-8000-000000000001")
        assertEquals("db01c56dc491fb3bd6dffdcf47cd2df51ccc3e514b382224be694711b518c413", BridgePolicy.digest(data))
    }
    @Test fun enrollmentPersistsAndSwitchingVpnRetainsIdentityButChangingCredentialsDoesNot() {
        val cfg = BridgeConfig(baseUrl = "https://bridge.example", wireguardUrl = "https://fallback.example", deviceId = id(), token = "a".repeat(48))
        assertTrue(cfg.valid()); assertFalse(cfg.enabled)
        val fallback = cfg.copy(useWireguard = true)
        assertEquals("https://fallback.example", fallback.activeUrl)
        assertEquals(BridgeController.enrollment(cfg), BridgeController.enrollment(fallback))
        assertNotEquals(BridgeController.enrollment(cfg), BridgeController.enrollment(cfg.copy(token = "b".repeat(48))))
        assertEquals(cfg, ConfigJson.merge(AppConfig(), ConfigJson.toJson(AppConfig(bridge = cfg))).bridge)
        for (url in listOf("http://bridge.example", "https://token@bridge.example", "https://bridge.example/path", "https://bridge.example?token=x"))
            assertFalse(cfg.copy(baseUrl = url).valid())
        assertFalse(cfg.copy(wireguardUrl = "", useWireguard = true).valid())
    }
    @Test fun clearSendDraftAndOrdinaryChatStayInTheirIntendedLanes() {
        for (text in listOf("Text Sam hello", "send a text to Sam", "Tell Yana I'm on my way")) {
            assertTrue(BridgePolicy.request(text)); assertFalse(BridgePolicy.draftRequest(text))
        }
        assertTrue(BridgePolicy.draftRequest("Draft a text to Sam"))
        assertFalse(BridgePolicy.request("Tell me a story"))
        assertFalse(BridgePolicy.request("What is the weather?"))
        assertTrue(BridgePolicy.sensitive("Your verification code is 123456"))
        assertTrue(BridgePolicy.destination("+14155550123"))
        for (number in listOf("911", "+18005550123", "+19005550123", "+442079460000", "+14165550123", "+12425550123", "+17875550123"))
            assertFalse(BridgePolicy.destination(number))
    }
    @Test fun tenPlugCyclesAndPowerGuardsNeverKeepRadiosOnInPocketMode() {
        val base = RelayPower(true, false, true, true, false, false, false, 100, 0)
        repeat(10) {
            assertTrue(base.holdRadios); assertTrue(base.sync) // Fully charged but plugged in.
            assertFalse(base.copy(plugged = false).holdRadios); assertFalse(base.copy(plugged = false).sync)
            assertTrue(base.copy(plugged = false, interactive = true).sync)
        }
        for (unsafe in listOf(base.copy(paused = true), base.copy(enabled = false), base.copy(deliberateAirplane = true),
            base.copy(powerSave = true), base.copy(batteryPercent = 14), base.copy(thermalStatus = 3))) {
            assertFalse(unsafe.holdRadios); assertFalse(unsafe.sync)
        }
        assertFalse(base.copy(docked = false).holdRadios)
    }

    @Test fun moderateOrHigherHeatPausesSyncAndReleasesDockedRadios() {
        val base = RelayPower(true, false, true, true, false, false, false, 100, PowerManager.THERMAL_STATUS_LIGHT)
        assertTrue(base.holdRadios)
        assertTrue(base.sync)
        for (level in PowerManager.THERMAL_STATUS_MODERATE..PowerManager.THERMAL_STATUS_SHUTDOWN) {
            val hot = base.copy(thermalStatus = level)
            assertFalse(hot.holdRadios)
            assertFalse(hot.sync)
            assertFalse(hot.copy(interactive = true).sync)
        }
    }
}
