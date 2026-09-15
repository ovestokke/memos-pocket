package com.vstokke.memos.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoPage
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.SpacePage
import com.vstokke.memos.reminders.NotificationPublisher
import com.vstokke.memos.reminders.ReminderCoordinator
import com.vstokke.memos.work.SyncScheduler
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AppDatabaseRobolectricTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databaseName = "sqlite-runtime-test.db"
    private fun <T> anyValue(): T = any<T>()
    private fun <T> eqValue(value: T): T = eq(value)

    private val baseMemo = Memo(
        name = "memos/base", content = "base", snippet = "base", visibility = "PRIVATE",
        createTime = Instant.EPOCH, updateTime = Instant.EPOCH, reminderTime = null,
        creator = "users/alice",
    )

    @Test
    fun `version one and two migrations preserve existing rows and delivery history`() {
        listOf(1, 2).forEach { version ->
            context.deleteDatabase(databaseName)
            val path = context.getDatabasePath(databaseName)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                database.execSQL(
                    "CREATE TABLE feed_memos (name TEXT PRIMARY KEY NOT NULL, content TEXT NOT NULL, " +
                        "snippet TEXT NOT NULL, visibility TEXT NOT NULL, create_epoch_ms INTEGER NOT NULL, " +
                        "update_epoch_ms INTEGER, reminder_epoch_ms INTEGER" +
                        if (version == 2) ", creator TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'NORMAL', " +
                            "pinned INTEGER NOT NULL DEFAULT 0, parent TEXT, space TEXT)" else ")",
                )
                database.execSQL(
                    "INSERT INTO feed_memos(name, content, snippet, visibility, create_epoch_ms) " +
                        "VALUES ('memos/old', 'kept', 'kept', 'PRIVATE', 0)",
                )
                database.execSQL(
                    "CREATE TABLE reminders (memo_name TEXT PRIMARY KEY NOT NULL, due_key TEXT NOT NULL, " +
                        "due_epoch_ms INTEGER NOT NULL, content TEXT NOT NULL, seen_generation TEXT NOT NULL)",
                )
                database.execSQL(
                    "CREATE TABLE reminder_deliveries (memo_name TEXT NOT NULL, due_key TEXT NOT NULL, " +
                        "delivered_at_ms INTEGER NOT NULL, PRIMARY KEY (memo_name, due_key))",
                )
                database.execSQL(
                    "INSERT INTO reminders VALUES ('memos/old', '2027-01-01T00:00:00Z', " +
                        "1798761600000, 'kept', 'one')",
                )
                database.execSQL(
                    "INSERT INTO reminder_deliveries VALUES ('memos/old', '2027-01-01T00:00:00Z', " +
                        "1798761600000)",
                )
                database.version = version
            }

            AppDatabase(context, databaseName).use { database ->
                assertEquals(4, database.readableDatabase.version)
                assertEquals("kept", database.feed().single().content)
                assertEquals(1, database.reminderRecords().size)
                assertTrue(database.pendingDue(Instant.parse("2027-01-01T00:00:01Z"), 60).isEmpty())
                assertTrue(database.pending().isEmpty())
            }
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `version three migration preserves outbox sent intent auth metadata and reminders`() {
        context.deleteDatabase(databaseName)
        val desired = baseMemo.copy(content = "queued")
        AppDatabase(context, databaseName).use { database ->
            database.replaceFeed(listOf(desired))
            database.queue(desired, baseMemo)
            val operation = database.pending().single()
            assertTrue(database.markSent(operation))
            database.setMetadata("owner", "https://example.com\\nusers/alice")
            database.setMetadata("auth_required", "true")
            database.upsertReminder(
                ReminderRecord(desired.name, Instant.parse("2027-01-01T12:00:00Z"), desired.content),
            )
            // Construct a real v3 file, which has offline data but not the v4 fence tables.
            database.writableDatabase.execSQL("DROP TABLE memo_sync_fences")
            database.writableDatabase.execSQL("DROP TABLE memo_sync_touches")
            database.writableDatabase.version = 3
        }

        AppDatabase(context, databaseName).use { database ->
            assertEquals(4, database.readableDatabase.version)
            assertEquals("queued", database.feed().single().content)
            assertEquals(desired, database.pending().single().desired)
            assertEquals(desired, database.pending().single().sent)
            assertEquals("https://example.com\\nusers/alice", database.metadata("owner"))
            assertEquals("true", database.metadata("auth_required"))
            assertEquals(1, database.reminderRecords().size)
            assertTrue(database.fences().isEmpty())
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `acknowledgement transaction rolls back outbox and fence together`() = withDatabase { database ->
        database.queue(baseMemo.copy(content = "local"), baseMemo)
        val operation = database.pending().single()
        database.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER fail_fence BEFORE INSERT ON memo_sync_fences " +
                "BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
        )
        try {
            assertTrue(runCatching {
                database.acknowledge(operation, operation.desired.copy(content = "server"))
            }.isFailure)
            assertEquals("local", database.pending().single().desired.content)
            assertTrue(database.fences().isEmpty())
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER fail_fence")
        }
    }

    @Test
    fun `complete quota scans evict stale clean rows and preserve pending work`() = withDatabase { database ->
        val old = baseMemo.copy(name = "memos/old", content = "old", snippet = "old", createTime = Instant.EPOCH)
        val newest = baseMemo.copy(
            name = "memos/newest", content = "newest", snippet = "newest",
            createTime = Instant.EPOCH.plusSeconds(2),
        )
        val pending = baseMemo.copy(name = "memos/pending", content = "pending", snippet = "pending")
        database.replaceFeed(listOf(old, newest))
        database.queue(pending, null)
        val firstScan = database.beginScan().also { it.inventoryComplete = true }

        database.applyScan(
            listOf(old, newest), emptyList(),
            byteLimit = CacheBudget.bytes(pending) + CacheBudget.bytes(newest), incomplete = true,
            pruneCandidates = setOf(old.name, newest.name), scan = firstScan,
        )
        assertEquals(setOf(newest.name, pending.name), database.feed().map { it.name }.toSet())
        assertEquals("true", database.metadata("incomplete"))

        val latest = baseMemo.copy(
            name = "memos/latest", content = "latest", snippet = "latest",
            createTime = Instant.EPOCH.plusSeconds(3),
        )
        val secondScan = database.beginScan().also { it.inventoryComplete = true }
        database.applyScan(
            listOf(newest, latest), emptyList(),
            byteLimit = CacheBudget.bytes(pending) + CacheBudget.bytes(latest), incomplete = true,
            pruneCandidates = setOf(newest.name), scan = secondScan,
        )
        assertEquals(setOf(latest.name, pending.name), database.feed().map { it.name }.toSet())
        assertTrue(database.pending().single().desired.name == pending.name)
    }

    @Test
    fun `verified remote deletion retires ACK fence and removes existing stale row under quota`() = withDatabase { database ->
        val reminded = baseMemo.copy(
            reminderTime = Instant.parse("2027-01-01T12:00:00Z"),
            content = "remember", snippet = "remember",
        )
        database.replaceFeed(listOf(reminded))
        database.upsertReminder(ReminderRecord(reminded.name, reminded.reminderTime!!, reminded.content))
        val desired = reminded.copy(content = "edited locally")
        database.queue(desired, reminded)
        val operation = database.pending().single()
        assertTrue(database.markSent(operation))
        val acknowledged = reminded.copy(content = "acknowledged", updateTime = Instant.now())
        database.acknowledge(operation, acknowledged)
        assertEquals(acknowledged, database.feed().single())
        assertEquals(acknowledged, database.fences().single().snapshot)

        // A stale list item is intentionally supplied; only the authoritative remote 404 is accepted.
        val scan = database.beginScan().also { it.inventoryComplete = true }
        database.applyScan(
            listOf(reminded), emptyList(), byteLimit = CacheBudget.bytes(reminded), incomplete = true,
            pruneCandidates = setOf(acknowledged.name), scan = scan,
            verifiedFences = mapOf(acknowledged.name to null),
        )
        database.reconcileReminders(emptyList())

        assertTrue(database.feed().isEmpty())
        assertTrue(database.fences().isEmpty())
        assertTrue(database.reminderRecords().isEmpty())
    }

    @Test
    fun `repository reminder merge removes a fenced memo after authoritative remote 404`() = withDatabase { database ->
        val reminded = baseMemo.copy(
            reminderTime = Instant.parse("2027-01-01T12:00:00Z"),
            content = "remember", snippet = "remember",
        )
        database.replaceFeed(listOf(reminded))
        database.upsertReminder(ReminderRecord(reminded.name, reminded.reminderTime!!, reminded.content))
        database.queue(reminded.copy(content = "acknowledged"), reminded)
        val operation = database.pending().single()
        assertTrue(database.markSent(operation))
        val acknowledged = reminded.copy(content = "acknowledged", updateTime = Instant.now())
        database.acknowledge(operation, acknowledged)

        val account = Account("https://example.com", "token", "users/alice", "Alice", true)
        val credentials = mock(CredentialStore::class.java)
        `when`(credentials.load()).thenReturn(account)
        val api = mock(MemosApi::class.java)
        `when`(api.supportsMemoReminderTime(anyString(), anyString())).thenReturn(true)
        `when`(api.listSpacesPage(anyValue(), anyValue(), anyInt()))
            .thenReturn(SpacePage(emptyList(), null))
        `when`(api.listPage(anyValue(), anyValue(), anyValue(), anyValue(), anyInt()))
            .thenReturn(MemoPage(emptyList(), null))
        doAnswer { null }.`when`(api).getMemo(anyValue(), eqValue(acknowledged.name))
        val repository = MemoRepository(
            credentials, database, api,
            ReminderCoordinator(context, database, NotificationPublisher(context)),
            mock(SyncScheduler::class.java),
        )

        val result = kotlinx.coroutines.runBlocking { repository.syncTurn() }

        assertTrue(result.pullCompleted)
        assertTrue(database.feed().isEmpty())
        assertTrue(database.fences().isEmpty())
        assertTrue(database.reminderRecords().isEmpty())
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        context.deleteDatabase(databaseName)
        val database = AppDatabase(context, databaseName)
        try {
            database.clearAll()
            block(database)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
