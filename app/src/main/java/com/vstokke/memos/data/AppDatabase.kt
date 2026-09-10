package com.vstokke.memos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.ReminderTime
import java.time.Instant
import java.util.UUID

class AppDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 2) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE feed_memos (
                name TEXT PRIMARY KEY NOT NULL,
                content TEXT NOT NULL,
                snippet TEXT NOT NULL,
                visibility TEXT NOT NULL,
                create_epoch_ms INTEGER NOT NULL,
                update_epoch_ms INTEGER,
                creator TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL DEFAULT 'NORMAL',
                pinned INTEGER NOT NULL DEFAULT 0,
                parent TEXT,
                space TEXT,
                reminder_epoch_ms INTEGER
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE reminders (
                memo_name TEXT PRIMARY KEY NOT NULL,
                due_key TEXT NOT NULL,
                due_epoch_ms INTEGER NOT NULL,
                content TEXT NOT NULL,
                seen_generation TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE reminder_deliveries (
                memo_name TEXT NOT NULL,
                due_key TEXT NOT NULL,
                delivered_at_ms INTEGER NOT NULL,
                PRIMARY KEY (memo_name, due_key)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX reminder_due_index ON reminders(due_epoch_ms)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN creator TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN state TEXT NOT NULL DEFAULT 'NORMAL'")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN parent TEXT")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN space TEXT")
        }
    }

    fun replaceFeed(memos: List<Memo>) {
        writableDatabase.transaction {
            delete("feed_memos", null, null)
            memos.forEach { memo ->
                insertOrThrow("feed_memos", null, memo.toValues())
            }
        }
    }

    fun prependFeed(memo: Memo, limit: Int = 1000) {
        val updated = listOf(memo) + feed().filterNot { it.name == memo.name }
        replaceFeed(updated.take(limit))
    }

    fun feed(): List<Memo> = readableDatabase.rawQuery(
        "SELECT name, content, snippet, visibility, create_epoch_ms, update_epoch_ms, reminder_epoch_ms, creator, state, pinned, parent, space FROM feed_memos ORDER BY pinned DESC, create_epoch_ms DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Memo(
                        name = cursor.getString(0),
                        content = cursor.getString(1),
                        snippet = cursor.getString(2),
                        visibility = cursor.getString(3),
                        createTime = Instant.ofEpochMilli(cursor.getLong(4)),
                        updateTime = cursor.instantOrNull(5),
                        reminderTime = cursor.instantOrNull(6),
                        creator = cursor.getString(7), state = cursor.getString(8), pinned = cursor.getInt(9) != 0,
                        parent = cursor.getString(10), space = cursor.getString(11),
                    ),
                )
            }
        }
    }

    fun reconcileReminders(records: List<ReminderRecord>) {
        val generation = UUID.randomUUID().toString()
        writableDatabase.transaction {
            records.forEach { reminder ->
                val dueKey = ReminderTime.toServer(reminder.dueAt)
                val values = ContentValues().apply {
                    put("memo_name", reminder.memoName)
                    put("due_key", dueKey)
                    put("due_epoch_ms", reminder.dueAt.toEpochMilli())
                    put("content", reminder.content)
                    put("seen_generation", generation)
                }
                insertWithOnConflict("reminders", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                update("reminders", values, "memo_name = ?", arrayOf(reminder.memoName))
            }
            delete("reminders", "seen_generation != ?", arrayOf(generation))
        }
    }

    fun upsertReminder(record: ReminderRecord) {
        val generation = UUID.randomUUID().toString()
        writableDatabase.transaction {
            val dueKey = ReminderTime.toServer(record.dueAt)
            val values = ContentValues().apply {
                put("memo_name", record.memoName)
                put("due_key", dueKey)
                put("due_epoch_ms", record.dueAt.toEpochMilli())
                put("content", record.content)
                put("seen_generation", generation)
            }
            insertWithOnConflict("reminders", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            update("reminders", values, "memo_name = ?", arrayOf(record.memoName))
        }
    }

    // Coordinator serializes delivery within this single-process app. Only acknowledge
    // after NotificationManager accepts publication, so process death leaves work retryable.
    fun pendingDue(now: Instant, catchUpSeconds: Long): List<ReminderRecord> {
        val claimed = mutableListOf<ReminderRecord>()
        writableDatabase.transaction {
            delete(
                "reminder_deliveries",
                "delivered_at_ms < ?",
                arrayOf(now.minusSeconds(DELIVERY_RETENTION_SECONDS).toEpochMilli().toString()),
            )
            rawQuery(
                """
                SELECT r.memo_name, r.due_key, r.due_epoch_ms, r.content
                FROM reminders r
                LEFT JOIN reminder_deliveries d
                  ON d.memo_name = r.memo_name AND d.due_key = r.due_key
                WHERE r.due_epoch_ms <= ? AND r.due_epoch_ms >= ?
                  AND d.memo_name IS NULL
                ORDER BY r.due_epoch_ms ASC
                """.trimIndent(),
                arrayOf(
                    now.toEpochMilli().toString(),
                    now.minusSeconds(catchUpSeconds).toEpochMilli().toString(),
                ),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val memoName = cursor.getString(0)
                    val dueKey = cursor.getString(1)
                    claimed += ReminderRecord(
                        memoName = memoName,
                        dueAt = Instant.parse(dueKey),
                        content = cursor.getString(3),
                    )
                }
            }
        }
        return claimed
    }

    fun nextDueAfter(now: Instant): Instant? = readableDatabase.rawQuery(
        """
        SELECT MIN(r.due_epoch_ms)
        FROM reminders r
        LEFT JOIN reminder_deliveries d
          ON d.memo_name = r.memo_name AND d.due_key = r.due_key
        WHERE r.due_epoch_ms > ? AND d.memo_name IS NULL
        """.trimIndent(),
        arrayOf(now.toEpochMilli().toString()),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) Instant.ofEpochMilli(cursor.getLong(0)) else null
    }

    fun removeReminder(name: String) {
        writableDatabase.delete("reminders", "memo_name = ?", arrayOf(name))
    }

    fun acknowledgeDelivery(record: ReminderRecord, now: Instant) {
        val values = ContentValues().apply {
            put("memo_name", record.memoName)
            put("due_key", ReminderTime.toServer(record.dueAt))
            put("delivered_at_ms", now.toEpochMilli())
        }
        writableDatabase.insertWithOnConflict("reminder_deliveries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun clearAll() {
        writableDatabase.transaction {
            delete("feed_memos", null, null)
            delete("reminders", null, null)
            delete("reminder_deliveries", null, null)
        }
    }

    private fun Memo.toValues() = ContentValues().apply {
        put("name", name)
        put("content", content)
        put("snippet", snippet)
        put("visibility", visibility)
        put("creator", creator); put("state", state); put("pinned", if (pinned) 1 else 0)
        put("parent", parent); put("space", space)
        put("create_epoch_ms", createTime.toEpochMilli())
        updateTime?.let { put("update_epoch_ms", it.toEpochMilli()) } ?: putNull("update_epoch_ms")
        reminderTime?.let { put("reminder_epoch_ms", it.toEpochMilli()) } ?: putNull("reminder_epoch_ms")
    }

    private fun android.database.Cursor.instantOrNull(index: Int): Instant? =
        if (isNull(index)) null else Instant.ofEpochMilli(getLong(index))

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "memos-pocket.db"
        const val DELIVERY_RETENTION_SECONDS = 48L * 60L * 60L
    }
}
