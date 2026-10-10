package dev.r1ptt.messages

import android.app.Application
import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsSystemStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: SmsStore
    private lateinit var mirror: SmsSystemStore
    private lateinit var provider: Provider
    class Provider : ContentProvider() {
        val rows = mutableMapOf<Long, ContentValues>()
        var queries = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.item/sms"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = (rows.keys.maxOrNull() ?: 0L) + 1
            rows[id] = ContentValues(requireNotNull(values)).apply { put("creator", RuntimeEnvironment.getApplication().packageName) }
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            row.putAll(requireNotNull(values)); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No delete is allowed")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, order: String?): Cursor {
            queries++
            assertArrayEquals(arrayOf("_id"), projection)
            assertEquals("address=? AND body=? AND date=? AND sub_id=? AND creator=?", selection)
            val args = requireNotNull(selectionArgs)
            return MatrixCursor(arrayOf("_id")).apply {
                rows.forEach { (id, v) -> if (listOf("address", "body", "date", "sub_id", "creator").map { v.getAsString(it) } == args.toList()) addRow(arrayOf(id)) }
            }
        }
    }
    @Before fun setup() {
        context.deleteDatabase("messages.db"); store = SmsStore(context); mirror = SmsSystemStore(context, store)
        provider = Provider().apply { attachInfo(context, ProviderInfo().apply { authority = "sms" }) }
        ShadowContentResolver.registerProviderInternal("sms", provider)
        shadowOf(context.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_SMS)
    }
    @After fun cleanup() { store.close() }
    @Test fun defaultHandlerPersistsOutgoingAndCallbacksWithoutDuplicateOrInboxScan() {
        val record = SmsRecord.outgoing("+15551234567", "Synthetic only", 1, 1, 1000).copy(systemOwned = true)
        store.outgoing(record, SmsDraft()); mirror.sync(record); mirror.sync(record)
        assertEquals(1, provider.rows.size); assertEquals(1, provider.queries)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.rows.values.single().getAsInteger("type"))
        val callback = SmsCallback(record.id, record.token, 0, false)
        store.update(callback) { it.sent(0, false, 32, 42) }
        assertEquals(1, store.pendingSystemCopies().size)
        mirror.sync(requireNotNull(store.record(record.id)))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.rows.values.single().getAsInteger("type"))
        assertEquals(42, provider.rows.values.single().getAsInteger("error_code"))
        assertTrue(store.pendingSystemCopies().isEmpty())
    }
    @Test fun providerInsertCrashGapRecoversOnlyExactKnownRow() {
        val record = SmsRecord("incoming-hash", "12345", "Synthetic incoming", 1000, 1, true, systemOwned = true)
        store.insert(record)
        provider.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("address", record.peer); put("body", record.body); put("date", record.createdAt); put("sub_id", 1)
        })
        mirror.sync(record)
        assertEquals(1, provider.rows.size)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, provider.rows.values.single().getAsInteger("type"))
    }
    @Test fun roleLossAndHistoricalCompanionRecordsNeverImportOrWriteSystemMessages() {
        val old = SmsRecord.outgoing("+15551234567", "Old test", 1, 1, 1000)
        mirror.sync(old); assertEquals(0, provider.queries)
        shadowOf(context.getSystemService(RoleManager::class.java)).removeHeldRole(RoleManager.ROLE_SMS)
        mirror.sync(old.copy(systemOwned = true))
        assertTrue(provider.rows.isEmpty()); assertEquals(0, provider.queries)
    }
}
