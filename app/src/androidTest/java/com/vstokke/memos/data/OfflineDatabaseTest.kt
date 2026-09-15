package com.vstokke.memos.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoSyncStatus
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.Space
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class OfflineDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".debug")) // Only uniquely named test databases below.
    }
    private val memo = Memo("memos/one", "Markdown æ", "æ", "PRIVATE", Instant.EPOCH,
        Instant.parse("2026-01-01T00:00:00.123456789Z"), Instant.parse("2027-01-01T00:00:00Z"), "users/alice")

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val db = AppDatabase(context, "offline-test.db")
        try { db.clearAll(); block(db) } finally { db.clearAll(); db.close() }
    }

    @Test fun versionOneAndTwoUpgradePreserveMemoAndReminderDelivery() {
        for (version in listOf(1, 2)) {
            context.deleteDatabase("offline-test.db")
            val path = context.getDatabasePath("offline-test.db")
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE feed_memos (name TEXT PRIMARY KEY NOT NULL, content TEXT NOT NULL, snippet TEXT NOT NULL, visibility TEXT NOT NULL, create_epoch_ms INTEGER NOT NULL, update_epoch_ms INTEGER, reminder_epoch_ms INTEGER" +
                    if (version == 2) ", creator TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'NORMAL', pinned INTEGER NOT NULL DEFAULT 0, parent TEXT, space TEXT)" else ")")
                db.execSQL("INSERT INTO feed_memos(name, content, snippet, visibility, create_epoch_ms) VALUES ('memos/old', 'kept', 'kept', 'PRIVATE', 0)")
                db.execSQL("CREATE TABLE reminders (memo_name TEXT PRIMARY KEY NOT NULL, due_key TEXT NOT NULL, due_epoch_ms INTEGER NOT NULL, content TEXT NOT NULL, seen_generation TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder_deliveries (memo_name TEXT NOT NULL, due_key TEXT NOT NULL, delivered_at_ms INTEGER NOT NULL, PRIMARY KEY(memo_name,due_key))")
                db.execSQL("INSERT INTO reminders VALUES ('memos/old', '2027-01-01T00:00:00Z', 1798761600000, 'kept', 'one')")
                db.execSQL("INSERT INTO reminder_deliveries VALUES ('memos/old', '2027-01-01T00:00:00Z', 1798761600000)")
                db.version = version
            }
            AppDatabase(context, "offline-test.db").use { db ->
                assertEquals(4, db.readableDatabase.version)
                assertEquals("kept", db.feed().single().content)
                assertEquals(1, db.reminderRecords().size)
                assertTrue(db.pendingDue(Instant.parse("2027-01-01T00:00:01Z"), 60).isEmpty())
                assertTrue(db.pending().isEmpty())
                db.clearAll()
            }
        }
    }

    @Test fun acknowledgementFenceRollbackLeavesOutboxAndDesiredViewIntact() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        val operation = db.pending().single()
        db.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER fail_fence BEFORE INSERT ON memo_sync_fences BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
        )
        try {
            assertTrue(runCatching { db.acknowledge(operation, operation.desired.copy(content = "server")) }.isFailure)
            assertEquals("local", db.pending().single().desired.content)
            assertTrue(db.fences().isEmpty())
        } finally {
            db.writableDatabase.execSQL("DROP TRIGGER fail_fence")
        }
    }

    @Test fun queueAndFrozenIntentSurviveReopenWithExactRevisionPrecision() = withDatabase { db ->
        db.replaceFeed(listOf(memo))
        val desired = memo.copy(content = "Offline edit")
        db.queue(desired, memo)
        assertTrue(db.markSent(db.pending().single()))
        val newer = desired.copy(content = "Newer offline edit", pinned = true, state = "ARCHIVED")
        db.queue(newer, desired)
        AppDatabase(context, "offline-test.db").use { reopened ->
            val op = reopened.pending().single()
            assertEquals(memo, op.base)
            assertEquals(desired, op.sent)
            assertEquals(newer, op.desired)
            assertEquals(2L, op.revision)
            assertEquals("ARCHIVED", reopened.localMemos().single().state)
        }
    }

    @Test fun desiredMemoAndOutboxCommitAtomicallyOnSQLiteFailure() = withDatabase { db ->
        db.replaceFeed(listOf(memo))
        db.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_queue BEFORE INSERT ON memo_outbox BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
        try {
            assertTrue(runCatching { db.queue(memo.copy(content = "Must roll back"), memo) }.isFailure)
            assertEquals(memo, db.feed().single())
            assertTrue(db.pending().isEmpty())
        } finally { db.writableDatabase.execSQL("DROP TRIGGER fail_queue") }
    }

    @Test fun acknowledgementKeepsNewerLocalEditAndAdvancesBaseline() = withDatabase { db ->
        db.queue(memo.copy(content = "first"), memo)
        val first = db.pending().single()
        db.markSent(first)
        db.queue(memo.copy(content = "second"), first.desired)
        db.acknowledge(first, first.desired.copy(updateTime = Instant.now()))
        assertEquals("second", db.localMemos().single().content)
        assertEquals("first", db.pending().single().base!!.content)
        assertNull(db.pending().single().sent)
    }

    @Test fun completeScanNeverOverwritesPendingConflictsOrTombstones() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo, deleted = true)
        db.markIssue(memo.name, MemoSyncStatus.CONFLICT, memo.copy(content = "server"))
        val stale = memo.copy(name = "memos/stale")
        db.prependFeed(stale)
        db.applyScan(listOf(memo.copy(content = "pulled")), listOf(Space("spaces/team", "Team", "Title survives")))
        assertTrue(db.localMemos().isEmpty())
        assertEquals("local", db.localMemos(true).single().content)
        assertEquals("server", db.pending().single().server!!.content)
        assertEquals("Team", db.spaces().single().title)
        assertEquals("true", db.metadata("spaces_known"))
        assertFalse(db.feed().any { it.name == stale.name })
    }

    @Test fun deleteBeforeDispatchCancelsCreateButDispatchedCreateKeepsTombstone() = withDatabase { db ->
        db.queue(memo, null)
        db.queue(memo, null, deleted = true)
        assertTrue(db.pending().isEmpty()); assertTrue(db.feed().isEmpty())
        db.queue(memo, null)
        db.markSent(db.pending().single())
        db.queue(memo, null, deleted = true)
        assertTrue(db.pending().single().deleted)
        assertEquals(memo, db.pending().single().sent)
    }

    @Test fun staleDispatchCannotResurrectCancelledCreate() = withDatabase { db ->
        db.queue(memo, null)
        val selected = db.pending().single()
        db.queue(memo, null, deleted = true)
        assertFalse(db.markSent(selected))
    }

    @Test fun conflictsResolveExplicitlyToServerOrNewLocalMemo() = withDatabase { db ->
        val desired = memo.copy(content = "local")
        val server = memo.copy(content = "server")
        db.queue(desired, memo)
        db.markIssue(memo.name, MemoSyncStatus.CONFLICT, server)
        val copy = desired.copy(name = "memos/new")
        db.resolve(memo.name, copy)
        assertEquals(server, db.feed().first { it.name == memo.name })
        assertEquals(copy, db.pending().single().desired)
        assertNull(db.pending().single().base)
        db.markIssue(copy.name, MemoSyncStatus.CONFLICT, null)
        db.resolve(copy.name, null)
        assertTrue(db.pending().isEmpty())
        assertEquals(listOf(server), db.feed())
    }

    @Test fun resolvingIgnoredCreateIdRemovesOrphanedLocalName() = withDatabase { db ->
        val local = memo.copy(name = "memos/mp-local", content = "local")
        val unexpected = memo.copy(name = "memos/server-generated", content = "server")
        db.queue(local, null)
        db.markIssue(local.name, MemoSyncStatus.CONFLICT, unexpected)

        db.resolve(local.name, null)

        assertEquals(listOf(unexpected), db.feed())
        assertTrue(db.pending().isEmpty())
    }

    @Test fun failedUploadRequiresExplicitRetryAndKeepsFrozenIntent() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        val operation = db.pending().single()
        assertTrue(db.markSent(operation))
        db.markIssue(memo.name, MemoSyncStatus.FAILED, null)

        assertTrue(db.retry(memo.name))
        val retried = db.pending().single()
        assertEquals(MemoSyncStatus.PENDING, retried.status)
        assertEquals("local", retried.desired.content)
        assertEquals("local", retried.sent!!.content)
        assertFalse(db.retry(memo.name))
    }

    @Test fun quotaCountsUtf8KeepsNewestAndNeverEvictsPending() = withDatabase { db ->
        val pending = memo.copy(name = "memos/pending", content = "æ", snippet = "") // two bytes
        db.queue(pending, null)
        val oldest = memo.copy(content = "1234", snippet = "", createTime = Instant.EPOCH)
        val newest = oldest.copy(name = "memos/new", createTime = Instant.EPOCH.plusSeconds(1))
        db.applyScan(listOf(oldest, newest), emptyList(), byteLimit = 6)
        assertEquals(setOf(pending.name, newest.name), db.feed().map { it.name }.toSet())
        assertEquals("true", db.metadata("incomplete"))
        db.applyScan(listOf(oldest, newest), emptyList(), byteLimit = 1)
        assertEquals(listOf(pending), db.feed())
        assertEquals(1, db.pending().size)
    }

    @Test fun staleListCannotOverwriteMemoAcknowledgedDuringScan() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        val operation = db.pending().single()
        val acknowledged = operation.desired.copy(updateTime = Instant.parse("2028-01-01T00:00:00Z"))
        db.acknowledge(operation, acknowledged)

        db.applyScan(listOf(memo.copy(content = "stale list")), emptyList(), pruneCandidates = emptySet())

        assertEquals(acknowledged, db.feed().single())
    }

    @Test fun completedPullCannotPruneConflictResolvedDuringScan() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        db.markIssue(memo.name, MemoSyncStatus.CONFLICT, memo)
        // At scan start this row was protected; user resolved it while pages were fetched.
        db.resolve(memo.name, null)
        db.applyScan(emptyList(), emptyList(), pruneCandidates = emptySet())
        assertEquals(listOf(memo), db.feed())
    }

    @Test fun ackFenceSurvivesReopenAndProtectsAgainstStaleList() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        val operation = db.pending().single()
        db.markSent(operation)
        val acknowledged = operation.desired.copy(content = "server", updateTime = Instant.now())
        db.acknowledge(operation, acknowledged)
        assertEquals(1, db.fences().size)

        AppDatabase(context, "offline-test.db").use { reopened ->
            assertEquals(acknowledged, reopened.fences().single().snapshot)
            val scan = reopened.beginScan()
            reopened.applyScan(
                listOf(memo.copy(content = "stale list")), emptyList(),
                pruneCandidates = emptySet(), scan = scan,
            )
            assertEquals(acknowledged, reopened.feed().single())
            assertEquals(1, reopened.fences().size)
        }
    }

    @Test fun ackFenceIsRetiredOnlyAfterAuthoritativeCorroboration() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo)
        val operation = db.pending().single()
        db.markSent(operation)
        val acknowledged = operation.desired.copy(content = "server", updateTime = Instant.now())
        db.acknowledge(operation, acknowledged)
        val scan = db.beginScan()
        db.applyScan(
            listOf(acknowledged), emptyList(), pruneCandidates = emptySet(), scan = scan,
            verifiedFences = mapOf(memo.name to acknowledged),
        )
        assertTrue(db.fences().isEmpty())
        assertEquals(acknowledged, db.feed().single())
    }

    @Test fun deletionFenceDoesNotResurrectFromStaleListAndRetiresOn404() = withDatabase { db ->
        db.queue(memo.copy(content = "local"), memo, deleted = true)
        val operation = db.pending().single()
        db.markSent(operation)
        db.acknowledge(operation, null)
        val scan = db.beginScan()
        db.applyScan(
            listOf(memo), emptyList(), pruneCandidates = emptySet(), scan = scan,
            verifiedFences = mapOf(memo.name to null),
        )
        assertTrue(db.feed().isEmpty())
        assertTrue(db.fences().isEmpty())
    }
}
