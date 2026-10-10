package dev.r1ptt.bridge

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.r1ptt.messages.SmsRecord
import dev.r1ptt.messages.SmsDraft
import dev.r1ptt.messages.SmsStore
import org.json.JSONObject
import java.util.UUID

data class BridgeJob(val id: String, val state: String, val payload: JSONObject?, val result: String?,
    val record: SmsRecord?, val digest: String?, val grantUntil: Long, val outcome: String?, val enrollment: String,
    val expires: Long, val receipt: String?)

/** Uses the same database/transaction as native SMS handoff, not a second outbox database. */
class BridgeJournal(private val store: SmsStore) {
    private val db get() = store.writableDatabase
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    fun peer(number: String): String = transaction {
        db.rawQuery("SELECT id FROM bridge_peers WHERE number=?", arrayOf(number)).use { c ->
            if (c.moveToFirst()) c.getString(0) else UUID.randomUUID().toString().also { id ->
                db.insertOrThrow("bridge_peers", null, ContentValues().apply { put("number", number); put("id", id); put("blocked", 0) })
            }
        }
    }
    fun blocked(number: String) = db.rawQuery("SELECT blocked FROM bridge_peers WHERE number=?", arrayOf(number)).use { it.moveToFirst() && it.getInt(0) != 0 }
    fun block(number: String) = transaction {
        peer(number)
        db.execSQL("UPDATE bridge_peers SET blocked=1 WHERE number=?", arrayOf(number))
        jobs().filter { it.record?.peer == number || it.payload?.optString("peer_id") == peer(number) }.forEach { cancel(it.id) }
    }
    fun enqueue(payload: JSONObject, outcome: String?, enrollment: String): String = transaction {
        val id = payload.getString("id")
        get(id)?.let { old ->
            check(old.payload?.toString() == payload.toString() && old.enrollment == enrollment) { "Command ID conflict" }
            return@transaction id
        }
        check(jobs().count { it.state !in BridgePolicy.terminal } < 20) { "Relay queue is full. Open SMS relay to review it." }
        db.insertOrThrow("bridge_jobs", null, ContentValues().apply {
            put("id", id); put("state", "queued"); put("payload", payload.toString()); put("outcome", outcome)
            put("enrollment", enrollment); put("expires", payload.getLong("expires")); put("created", System.currentTimeMillis() / 1000)
        })
        id
    }
    fun enqueueSelected(payload: JSONObject, enrollment: String): String = transaction {
        check(payload.getString("lane") == "selected")
        val id = payload.getString("id")
        get(id)?.let { check(it.enrollment == enrollment); return@transaction id }
        val peer = payload.getString("peer_id")
        check(jobs().none { it.state !in BridgePolicy.terminal && it.payload?.optString("peer_id") == peer }) { "Wait for this conversation's current request first." }
        enqueue(payload, null, enrollment)
    }
    fun get(id: String): BridgeJob? = db.rawQuery("SELECT * FROM bridge_jobs WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else {
            fun str(key: String) = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
            BridgeJob(id, str("state")!!, str("payload")?.let(::JSONObject), str("result"), str("record")?.let(SmsRecord::decode),
                str("digest"), c.getLong(c.getColumnIndexOrThrow("grant_until")), str("outcome"), str("enrollment")!!,
                c.getLong(c.getColumnIndexOrThrow("expires")), str("receipt"))
        }
    }
    fun jobs(): List<BridgeJob> = db.rawQuery("SELECT id FROM bridge_jobs ORDER BY (state IN ('queued','running','ready','frozen','stopping')) DESC,created DESC LIMIT 100", null).use { c ->
        buildList { while (c.moveToNext()) get(c.getString(0))?.let(::add) }
    }
    fun draft(id: String, value: SmsDraft) = transaction {
        check(get(id)?.state == "ready")
        store.addReplyRequest(id, value)
        update(id, "draft")
    }
    fun update(id: String, state: String, result: String? = null) = transaction {
        val old = get(id) ?: return@transaction
        if (old.state in BridgePolicy.terminal || old.state == "stopping") return@transaction
        db.update("bridge_jobs", ContentValues().apply { put("state", state); if (result != null) put("result", result) }, "id=?", arrayOf(id))
    }
    fun freeze(id: String, record: SmsRecord, digest: String) = transaction {
        val old = requireNotNull(get(id))
        check(old.state == "ready")
        check(old.record == null || old.record == record)
        db.update("bridge_jobs", ContentValues().apply { put("record", record.encode()); put("digest", digest); put("state", "frozen") }, "id=?", arrayOf(id))
    }
    fun grant(id: String, digest: String, until: Long) = transaction {
        val old = requireNotNull(get(id))
        check(old.state == "frozen" && old.digest == digest && until <= old.expires)
        db.execSQL("UPDATE bridge_jobs SET grant_until=? WHERE id=?", arrayOf<Any>(until, id))
    }
    fun cancel(id: String) = transaction {
        val old = get(id) ?: return@transaction
        if (old.state !in BridgePolicy.terminal) db.execSQL("UPDATE bridge_jobs SET state='stopping',grant_until=0 WHERE id=?", arrayOf(id))
    }
    fun stopped(id: String) { db.execSQL("UPDATE bridge_jobs SET state='cancelled' WHERE id=? AND state='stopping'", arrayOf(id)) }
    fun receipt(id: String, status: String) { db.execSQL("UPDATE bridge_jobs SET receipt=? WHERE id=?", arrayOf(status, id)) }
    fun prune(now: Long) {
        db.execSQL("UPDATE bridge_jobs SET state='expired',grant_until=0 WHERE expires<=? AND state IN ('queued','running','ready','frozen')", arrayOf(now))
        db.execSQL("UPDATE bridge_jobs SET payload=NULL,result=NULL,record=NULL WHERE created<? AND state IN ('cancelled','expired','failed','unresolved','revoked','draft','explained','clarify','chat','handoff')", arrayOf(now - BridgePolicy.RETENTION_SECONDS))
    }

    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE bridge_jobs(id TEXT PRIMARY KEY,state TEXT NOT NULL,payload TEXT,result TEXT,record TEXT,digest TEXT,grant_until INTEGER NOT NULL DEFAULT 0,outcome TEXT,enrollment TEXT NOT NULL,expires INTEGER NOT NULL,created INTEGER NOT NULL,receipt TEXT)")
            db.execSQL("CREATE TABLE bridge_peers(number TEXT PRIMARY KEY,id TEXT UNIQUE NOT NULL,blocked INTEGER NOT NULL DEFAULT 0)")
        }
        /** Runs inside SmsStore.outgoing's native-attempt transaction. No retry after this claim. */
        fun claim(db: SQLiteDatabase, id: String, record: SmsRecord, now: Long) {
            db.rawQuery("SELECT state,record,expires,grant_until FROM bridge_jobs WHERE id=?", arrayOf(id)).use { c ->
                check(c.moveToFirst() && c.getString(0) == "frozen" && c.getLong(2) > now && c.getLong(3) > now) { "Send authorization expired or was consumed" }
                val frozen = SmsRecord.decode(c.getString(1))
                // The native executor stamps handoff time/role/diagnostics at dispatch. Only
                // those evidence fields may change; identity, token, body, SIM and parts may not.
                check(frozen.copy(createdAt = record.createdAt, systemOwned = record.systemOwned, sendEvidence = record.sendEvidence) == record) { "Native attempt changed after authorization" }
            }
            db.rawQuery("SELECT blocked FROM bridge_peers WHERE number=?", arrayOf(record.peer)).use { check(!it.moveToFirst() || it.getInt(0) == 0) { "Recipient blocked" } }
            db.execSQL("UPDATE bridge_jobs SET state='handoff',grant_until=0 WHERE id=?", arrayOf(id))
        }
    }
}
