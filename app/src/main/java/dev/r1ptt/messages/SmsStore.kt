package dev.r1ptt.messages

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteConstraintException

/** App-private SMS data, separate from AI History. SQLite transactions commit before any send. */
class SmsStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, peer TEXT NOT NULL, created INTEGER NOT NULL, unread INTEGER NOT NULL, record TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_peer ON messages(peer, created)")
        db.execSQL("CREATE TABLE draft (id INTEGER PRIMARY KEY CHECK (id = 1), peer TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE recipients (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, number TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported Messages database version") // never erase messages to recover a schema error
    }

    fun draft(): SmsDraft = readableDatabase.rawQuery("SELECT peer, body FROM draft WHERE id=1", null).use { c ->
        if (c.moveToFirst()) SmsDraft(c.getString(0), c.getString(1)) else SmsDraft()
    }

    fun saveDraft(draft: SmsDraft) {
        writableDatabase.insertWithOnConflict("draft", null, ContentValues().apply {
            put("id", 1); put("peer", draft.peer); put("body", draft.body)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) { "Draft was not saved" } }
    }

    fun insert(record: SmsRecord): Boolean = try {
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("id", record.id); put("peer", record.peer); put("created", record.createdAt)
            put("unread", if (record.incoming) 1 else 0); put("record", record.encode())
        })
        true
    } catch (e: SQLiteConstraintException) {
        val duplicate = readableDatabase.rawQuery("SELECT id FROM messages WHERE id=?", arrayOf(record.id)).use { it.moveToFirst() }
        if (!duplicate) throw e
        false
    }

    fun outgoing(record: SmsRecord, draft: SmsDraft) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(insert(record)) { "This send was already recorded" }
            // The draft may have changed while the review was open; clear only that exact draft.
            db.delete("draft", "id=1 AND peer=? AND body=?", arrayOf(draft.peer, draft.body))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun update(callback: SmsCallback, change: (SmsRecord) -> SmsRecord): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT record FROM messages WHERE id=?", arrayOf(callback.id)).use { c ->
                if (c.moveToFirst()) SmsRecord.decode(c.getString(0)) else null
            } ?: return false
            if (!callback.accepts(old)) return false
            val next = change(old)
            if (next != old) db.update("messages", ContentValues().apply { put("record", next.encode()) }, "id=?", arrayOf(old.id))
            db.setTransactionSuccessful()
            return next != old
        } finally { db.endTransaction() }
    }

    fun threads(): List<SmsThread> = readableDatabase.rawQuery(
        "SELECT m.peer,m.record,(SELECT MAX(unread) FROM messages u WHERE u.peer=m.peer) FROM messages m " +
            "WHERE m.rowid=(SELECT x.rowid FROM messages x WHERE x.peer=m.peer ORDER BY x.created DESC,x.rowid DESC LIMIT 1) ORDER BY m.created DESC LIMIT 100", null,
    ).use { c -> buildList { while (c.moveToNext()) add(SmsThread(c.getString(0), SmsRecord.decode(c.getString(1)), c.getInt(2) != 0)) } }

    /** Show the newest 100; older records remain in the database. */
    fun conversation(peer: String): List<SmsRecord> = readableDatabase.rawQuery(
        "SELECT record FROM messages WHERE peer=? ORDER BY created DESC,rowid DESC LIMIT 100", arrayOf(peer),
    ).use { c -> buildList { while (c.moveToNext()) add(SmsRecord.decode(c.getString(0))) }.reversed() }

    fun markRead(peer: String) { writableDatabase.update("messages", ContentValues().apply { put("unread", 0) }, "peer=?", arrayOf(peer)) }

    fun recipients(): List<SmsRecipient> = readableDatabase.rawQuery("SELECT id,name,number FROM recipients ORDER BY name COLLATE NOCASE,id", null).use { c ->
        buildList { while (c.moveToNext()) add(SmsRecipient(c.getLong(0), c.getString(1), c.getString(2))) }
    }
    fun addRecipient(name: String, number: String) {
        require(name.isNotBlank() && name.length <= 80 && SmsAddress.normalize(number) == number)
        writableDatabase.insertOrThrow("recipients", null, ContentValues().apply { put("name", name.trim()); put("number", number) })
    }
    fun removeRecipient(id: Long) { writableDatabase.delete("recipients", "id=?", arrayOf(id.toString())) }
}
