package com.vstokke.memos.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vstokke.memos.domain.ReminderRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReminderLedgerTest {
    @Test
    fun pendingBatchSurvivesReopenAndOnlyPublishedItemsAreAcknowledged() {
        // A dedicated test database, never the signed-in app database.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val now = Instant.parse("2026-05-01T12:00:01Z")
        val first = ReminderRecord("memos/first", Instant.parse("2026-05-01T12:00:00.123456Z"), "Test")
        val second = first.copy(memoName = "memos/second")
        var db = AppDatabase(context, "reminder-ledger-test.db")
        try {
            db.clearAll()
            db.reconcileReminders(listOf(first, second))
            assertEquals(setOf(first, second), db.pendingDue(now, 86400).toSet())
            db.close()
            db = AppDatabase(context, "reminder-ledger-test.db")
            assertEquals(setOf(first, second), db.pendingDue(now, 86400).toSet())
            db.acknowledgeDelivery(first, now)
            db.close()
            db = AppDatabase(context, "reminder-ledger-test.db")
            assertEquals(listOf(second), db.pendingDue(now, 86400))
            // A publication failure has no acknowledgment; retry keeps exact nanoseconds.
            assertEquals(second.dueAt, db.pendingDue(now, 86400).single().dueAt)
            db.acknowledgeDelivery(second, now)
            db.reconcileReminders(listOf(first, second))
            assertTrue(db.pendingDue(now, 86400).isEmpty())
        } finally {
            db.clearAll()
            db.close()
        }
    }
}
