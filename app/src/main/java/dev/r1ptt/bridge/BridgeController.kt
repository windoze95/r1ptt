package dev.r1ptt.bridge

import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import dev.r1ptt.App
import dev.r1ptt.OutcomeStatus
import dev.r1ptt.data.BridgeConfig
import dev.r1ptt.messages.*
import dev.r1ptt.net.CallRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/** Only enqueueOwner (trusted UI) mints authority. Sync can never invent a local command. */
class BridgeController(private val app: App) {
    private val store by lazy { SmsStore(app) }
    val journal by lazy { BridgeJournal(store) }
    private val http = BridgeHttp()
    private val mutex = Mutex()
    private val leases = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val prefs = app.getSharedPreferences("relay", 0)
    private val _status = MutableStateFlow("Relay off")
    val status: StateFlow<String> = _status
    @Volatile private var calls = CallRegistry()
    @Volatile var allowed = false
        private set
    val lastContact: Long get() = prefs.getLong("contact", 0)
    val meteredBytes: Long get() = if (prefs.getLong("day", -1) == System.currentTimeMillis() / 86_400_000) prefs.getLong("bytes", 0) else 0

    suspend fun enqueueOwner(text: String, outcome: String): String {
        val cfg = app.store.value.bridge
        check(cfg.enabled && cfg.valid() && !cfg.paused) { "Resume SMS relay before sending a relay command." }
        check(app.smsAssistant.enabled && app.screen.isScreenOn() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the R1 to issue an SMS command." }
        require(BridgePolicy.request(text) && text.length <= 6000)
        require(!BridgePolicy.sensitive(text)) { "Security codes and credentials stay local. Use the native Messages editor." }
        val mode = if (BridgePolicy.draftRequest(text)) "draft" else "send"
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (mode == "send") check(app.sms.canSend && app.store.value.power.cellular && SubscriptionManager.isValidSubscriptionId(sub)) { "Enable SMS access and select a SIM first." }
        val id = withContext(Dispatchers.IO) {
            journal.enqueue(payload(outcome, "owner", UUID.randomUUID().toString(), text, mode, sub), outcome, enrollment(cfg))
        }
        RelayService.start(app)
        _status.value = "Owner request saved · waiting for Hermes"
        return id
    }

    suspend fun select(record: SmsRecord): String {
        val cfg = app.store.value.bridge
        check(cfg.enabled && cfg.valid() && !cfg.paused)
        check(app.screen.isScreenOn() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        require(record.incoming && BridgePolicy.destination(record.peer) && !BridgePolicy.sensitive(record.body)) { "This message must stay on the R1." }
        val id = withContext(Dispatchers.IO) {
            check(store.record(record.id) == record && !journal.blocked(record.peer))
            check(store.recipients().any { it.number == record.peer }) { "Only a saved recipient's selected message can be shared." }
            val selectedId = selectedId(cfg.deviceId, record.id)
            val peer = journal.peer(record.peer)
            journal.enqueueSelected(payload(selectedId, "selected", peer, record.body, "explain", record.subscriptionId), enrollment(cfg))
        }
        RelayService.start(app)
        return id
    }

    private fun payload(id: String, lane: String, peer: String, text: String, mode: String, sim: Int) = JSONObject()
        .put("version", BridgePolicy.VERSION).put("id", id).put("lane", lane)
        .put("conversation", app.history.convId.let { runCatching { UUID.fromString(it).toString() }.getOrElse { UUID.randomUUID().toString() } })
        .put("peer_id", peer).put("text", text).put("expires", System.currentTimeMillis() / 1000 + BridgePolicy.TTL_SECONDS)
        .put("sim", sim).put("mode", mode)

    fun availability(value: Boolean, reason: String) {
        allowed = value
        if (!value) { calls.cancelAll(); _status.value = reason }
    }
    fun interrupt() { calls.cancelAll() }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        journal.cancel(id) // Commit first. Closing a request is not server cancellation.
        finishOutcome(id)
        calls.cancelAll()
        _status.value = "Stopping request · any Android handoff still tracks receipts"
    }
    suspend fun block(number: String) = withContext(Dispatchers.IO) { journal.block(number); calls.cancelAll() }
    fun canDispatch(id: String, requireGrant: Boolean = true): Boolean {
        val cfg = app.store.value.bridge
        return allowed && cfg.enabled && !cfg.paused && !app.radio.deliberateAirplane && app.smsAssistant.enabled &&
            (!requireGrant || (leases[id] ?: 0) > SystemClock.elapsedRealtime()) && journal.get(id)?.let {
            it.state == "frozen" && it.enrollment == enrollment(cfg) && it.expires > System.currentTimeMillis() / 1000
        } == true
    }

    /** One bounded sync pass. No alarms/work manager, and no work while pocket radios sleep. */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!allowed) return@withLock false
        val cfg = app.store.value.bridge
        if (!cfg.enabled || cfg.paused || !cfg.valid()) return@withLock false
        val activeCalls = CallRegistry().also { calls = it }
        withContext(Dispatchers.IO) {
            journal.prune(System.currentTimeMillis() / 1000)
            try {
                val identity = request(cfg, "/v1/device", null, activeCalls)
                check(identity.getInt("version") == BridgePolicy.VERSION && identity.getString("device_id") == cfg.deviceId)
                check(kotlin.math.abs(identity.getLong("server_time") - System.currentTimeMillis() / 1000) <= 30) { "Device clock differs from relay" }
                prefs.edit().putLong("contact", System.currentTimeMillis()).apply()
                _status.value = "Relay connected"
                for (job in journal.jobs().reversed()) {
                    currentCoroutineContext().ensureActive()
                    if (!allowed) break
                    if (job.enrollment != enrollment(cfg)) { journal.update(job.id, "revoked"); finishOutcome(job.id); continue }
                    try { process(cfg, job, activeCalls) }
                    catch (error: BridgeError) {
                        if (error.code in setOf(401, 403)) {
                            journal.update(job.id, "revoked"); _status.value = "Relay access or request revoked"
                            if (error.code == 401) break
                        } else if (error.code in setOf(400, 404, 409, 413, 429)) {
                            journal.update(job.id, if (error.code == 404) "unresolved" else "failed")
                            _status.value = "Request held · open SMS relay"
                        } else throw error
                    } catch (error: CancellationException) { throw error }
                    catch (_: IllegalArgumentException) { journal.update(job.id, "clarify"); _status.value = "Request needs clarification" }
                    catch (_: IllegalStateException) { journal.update(job.id, "failed"); _status.value = "Request held · check SMS access, SIM and relay settings" }
                    finally { finishOutcome(job.id) }
                }
                journal.jobs().any { it.state !in BridgePolicy.terminal }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                _status.value = "Hermes connection unavailable · requests saved"
                true
            }
        }
    }

    private suspend fun process(cfg: BridgeConfig, initial: BridgeJob, calls: CallRegistry) {
        var job = journal.get(initial.id) ?: return
        val path = "/v1/commands/${job.id}"
        if (job.state == "handoff") {
            val record = job.record?.let { store.record(it.id) } ?: return
            val status = record.status(System.currentTimeMillis()).name
            if (job.receipt != status) {
                request(cfg, "$path/receipt", JSONObject().put("attempt", record.id).put("status", status), calls)
                journal.receipt(job.id, status)
            }
            return
        }
        if (job.state == "stopping") {
            try {
                val response = request(cfg, "$path/cancel", JSONObject(), calls)
                if (response.getString("state") in setOf("cancelled", "failed", "expired", "unresolved", "revoked")) journal.stopped(job.id)
            } catch (error: BridgeError) { if (error.code == 404) journal.stopped(job.id) else throw error }
            return
        }
        if (job.state in BridgePolicy.terminal) return
        if (job.state in setOf("queued", "running")) {
            val response = if (job.state == "queued") request(cfg, "/v1/commands", requireNotNull(job.payload), calls)
                else request(cfg, path, null, calls)
            check(response.getString("id") == job.id && response.getInt("version") == BridgePolicy.VERSION)
            val state = response.getString("state")
            check(state in setOf("queued", "running", "ready", "expired", "failed", "unresolved", "revoked", "cancelled"))
            journal.update(job.id, state, response.optString("result").takeUnless { it.isBlank() || it == "null" })
            job = requireNotNull(journal.get(job.id))
        }
        if (job.state == "ready") {
            val original = requireNotNull(job.payload)
            if (original.getString("lane") == "selected") { journal.update(job.id, "explained"); _status.value = "Selected message explanation ready"; return }
            val result = requireNotNull(job.result)
            if (original.getString("mode") == "draft") {
                val value = JSONObject(result)
                if (value.optString("kind") != "draft") { journal.update(job.id, "clarify"); return }
                check(value.length() == 3)
                val recipient = value.getString("recipient")
                check(recipient.isNotBlank() && original.getString("text").contains(recipient))
                val resolved = SmsRecipientResolver.resolve(recipient, store.recipients()) as? SmsResolution.Number
                check(resolved != null && !journal.blocked(resolved.number))
                val body = value.getString("body")
                require(body.isNotBlank() && body.length <= SmsRecord.MAX_DRAFT_CHARS)
                // A separate reviewable draft cannot overwrite the owner's manual draft.
                journal.draft(job.id, SmsDraft(resolved.number, body))
                _status.value = "Draft ready in Messages · unsent"
                return
            }
            when (val resultIntent = SmsIntent.decode(result, original.getString("text"))) {
                is SmsIntent.Send -> {
                    val resolved = SmsRecipientResolver.resolve(resultIntent.action.recipient, store.recipients()) as? SmsResolution.Number
                    require(resolved != null && BridgePolicy.destination(resolved.number))
                    check(!journal.blocked(resolved.number))
                    check(original.getInt("sim") == SubscriptionManager.getDefaultSmsSubscriptionId())
                    val review = app.sms.prepare(SmsDraft(resolved.number, resultIntent.action.body), job.outcome)
                    require(review.texts.size <= 3)
                    val frozen = frozen(review.record)
                    journal.freeze(job.id, review.record, BridgePolicy.digest(frozen))
                }
                SmsIntent.Clarify -> { journal.update(job.id, "clarify"); _status.value = "Use one exact saved name or full country-coded number"; return }
                SmsIntent.Chat -> { journal.update(job.id, "chat"); _status.value = "No SMS send requested"; return }
            }
            job = requireNotNull(journal.get(job.id))
        }
        if (job.state != "frozen" || !canDispatch(job.id, requireGrant = false) || app.turns.busy || app.smsBusy || app.updates.installing) return
        val record = requireNotNull(job.record)
        val frozen = frozen(record)
        check(BridgePolicy.digest(frozen) == job.digest && !journal.blocked(record.peer))
        val response = request(cfg, "$path/freeze", frozen, calls)
        check(response.getString("digest") == job.digest)
        val grantStarted = SystemClock.elapsedRealtime()
        val grant = request(cfg, "$path/grant", JSONObject().put("digest", job.digest).put("attempt", record.id), calls)
        check(grant.getString("id") == job.id && grant.getString("digest") == job.digest && grant.getString("attempt") == record.id)
        check(grant.getLong("valid_until") > System.currentTimeMillis() / 1000 && SystemClock.elapsedRealtime() - grantStarted < 25_000)
        journal.grant(job.id, requireNotNull(job.digest), grant.getLong("valid_until"))
        leases[job.id] = minOf(grantStarted + 25_000, SystemClock.elapsedRealtime() + (grant.getLong("valid_until") - System.currentTimeMillis() / 1000) * 1000)
        withContext(Dispatchers.Main) {
            if (!canDispatch(job.id)) return@withContext
            val prepared = app.sms.prepare(SmsDraft(record.peer, record.body), job.outcome)
            check(prepared.record.subscriptionId == record.subscriptionId && prepared.texts.size == record.parts.size)
            val review = SmsReview(prepared.draft, record, prepared.texts, prepared.simLabel, job.id)
            suspendCancellableCoroutine<Unit> { continuation ->
                app.sms.send(review, clearDraft = false) { consumed, message ->
                    leases.remove(job.id)
                    _status.value = message
                    if (consumed && app.screen.isScreenOn() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) runCatching {
                        app.startActivity(Intent(app, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SmsAssistant.THREAD, record.peer))
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private fun finishOutcome(id: String) {
        val job = journal.get(id) ?: return
        val status = when (job.state) {
            "cancelled", "stopping" -> OutcomeStatus.CANCELLED
            "draft", "explained", "chat" -> OutcomeStatus.COMPLETED
            "clarify" -> OutcomeStatus.CLARIFY
            "expired", "failed", "unresolved", "revoked" -> OutcomeStatus.FAILED
            else -> return
        }
        job.outcome?.let { app.outcomes.finishIfOpen(it, status) }
    }

    private suspend fun request(cfg: BridgeConfig, path: String, body: JSONObject?, calls: CallRegistry): JSONObject {
        currentCoroutineContext().ensureActive()
        check(allowed && cfg == app.store.value.bridge)
        return http.call(cfg, path, body, calls) { bytes ->
            if (app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) {
                val used = meteredBytes + bytes
                prefs.edit().putLong("day", System.currentTimeMillis() / 86_400_000).putLong("bytes", used).commit()
                check(used <= BridgePolicy.METERED_BYTES_PER_DAY) { "Relay mobile data budget reached" }
            }
        }
    }

    companion object {
        fun selectedId(device: String, message: String): String = UUID.nameUUIDFromBytes("robotOS:selected:$device:$message".toByteArray(Charsets.UTF_8)).toString()
        fun frozen(record: SmsRecord) = JSONObject().put("recipient", record.peer).put("body", record.body)
            .put("sim", record.subscriptionId).put("parts", record.parts.size).put("attempt", record.id)
        fun enrollment(cfg: BridgeConfig): String = MessageDigest.getInstance("SHA-256").digest("${cfg.baseUrl}\n${cfg.wireguardUrl}\n${cfg.wireguardAddress}\n${cfg.deviceId}\n${cfg.token}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
