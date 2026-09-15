package com.vstokke.memos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vstokke.memos.domain.Space
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoSyncStatus
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.ReminderTime
import java.time.Instant
import java.util.UUID

data class SyncFence(
    val name: String,
    val generation: Long,
    val snapshot: Memo?,
    val deleted: Boolean,
)

data class ScanSnapshot(
    val touchGeneration: Long,
    val fences: List<SyncFence>,
    /** Set by the repository only after its resumable inventory cursor reaches the end. */
    var inventoryComplete: Boolean = false,
)

class AppDatabase(context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(context, databaseName, null, 4) {
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
        createOfflineTables(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN creator TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN state TEXT NOT NULL DEFAULT 'NORMAL'")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN parent TEXT")
            database.execSQL("ALTER TABLE feed_memos ADD COLUMN space TEXT")
        }
        if (oldVersion < 3) createOfflineTables(database)
        if (oldVersion < 4) createSyncFenceTables(database)
    }

    private fun createOfflineTables(database: SQLiteDatabase) {
        database.execSQL("ALTER TABLE feed_memos ADD COLUMN snapshot TEXT")
        database.execSQL("CREATE TABLE memo_outbox (name TEXT PRIMARY KEY NOT NULL, base TEXT, desired TEXT NOT NULL, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, sent TEXT, sent_deleted INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, server TEXT)")
        database.execSQL("CREATE TABLE offline_spaces (name TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL)")
        database.execSQL("CREATE TABLE sync_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        createSyncFenceTables(database)
    }

    private fun createSyncFenceTables(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS memo_sync_fences (name TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL, snapshot TEXT, deleted INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS memo_sync_touches (name TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL)")
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
        "SELECT name, content, snippet, visibility, create_epoch_ms, update_epoch_ms, reminder_epoch_ms, creator, state, pinned, parent, space, snapshot FROM feed_memos ORDER BY pinned DESC, create_epoch_ms DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    if (!cursor.isNull(12)) MemoJson.decode(cursor.getString(12)) else Memo(
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

    fun metadata(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM sync_metadata WHERE key = ?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMetadata(key: String, value: String) {
        writableDatabase.insertWithOnConflict("sync_metadata", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun spaces(): List<Space> = readableDatabase.rawQuery(
        "SELECT name, title, description FROM offline_spaces ORDER BY title", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Space(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
    } }

    fun pending(): List<PendingMemo> = readableDatabase.rawQuery(
        "SELECT desired, base, deleted, revision, sent, sent_deleted, status, server FROM memo_outbox ORDER BY name", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(PendingMemo(
            MemoJson.decode(cursor.getString(0)), if (cursor.isNull(1)) null else MemoJson.decode(cursor.getString(1)),
            cursor.getInt(2) != 0, cursor.getLong(3), if (cursor.isNull(4)) null else MemoJson.decode(cursor.getString(4)),
            cursor.getInt(5) != 0, cursor.getString(6), if (cursor.isNull(7)) null else MemoJson.decode(cursor.getString(7)),
        ))
    } }

    /** Captures the durable touch watermark and fences before a network scan begins. */
    fun beginScan(): ScanSnapshot {
        val touchGeneration = metadata(TOUCH_GENERATION)?.toLongOrNull() ?: 0L
        return ScanSnapshot(touchGeneration, fences())
    }

    fun fences(): List<SyncFence> = readableDatabase.rawQuery(
        "SELECT name, generation, snapshot, deleted FROM memo_sync_fences ORDER BY name", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(SyncFence(
            name = cursor.getString(0),
            generation = cursor.getLong(1),
            snapshot = if (cursor.isNull(2)) null else MemoJson.decode(cursor.getString(2)),
            deleted = cursor.getInt(3) != 0,
        ))
    } }

    fun localMemos(includeDeleted: Boolean = false): List<Memo> {
        val operations = pending().associateBy { it.desired.name }
        return feed().mapNotNull { memo ->
            val operation = operations[memo.name]
            if (!includeDeleted && operation?.deleted == true) null
            else memo.copy(syncStatus = operation?.status ?: MemoSyncStatus.SYNCED)
        }
    }

    private fun SQLiteDatabase.upsertMemo(memo: Memo) {
        insertWithOnConflict("feed_memos", null, memo.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    // The desired view and its durable upload intent always commit together.
    fun queue(desired: Memo, base: Memo?, deleted: Boolean = false) {
        writableDatabase.transaction {
            touch(desired.name)
            val old = pending().firstOrNull { it.desired.name == desired.name }
            if (deleted && old != null && old.base == null && old.sent == null) {
                delete("memo_outbox", "name = ?", arrayOf(desired.name))
                delete("feed_memos", "name = ?", arrayOf(desired.name))
            } else {
                upsertMemo(desired)
                val values = ContentValues().apply {
                    put("name", desired.name); put("desired", MemoJson.encode(desired))
                    put("base", old?.base?.let(MemoJson::encode) ?: if (old == null) base?.let(MemoJson::encode) else null)
                    put("deleted", if (deleted) 1 else 0); put("revision", (old?.revision ?: 0) + 1)
                    put("sent", old?.sent?.let(MemoJson::encode)); put("sent_deleted", if (old?.sentDeleted == true) 1 else 0)
                    put("status", old?.status?.takeIf { it == MemoSyncStatus.CONFLICT || it == MemoSyncStatus.FAILED } ?: MemoSyncStatus.PENDING)
                    put("server", old?.server?.let(MemoJson::encode))
                }
                insertWithOnConflict("memo_outbox", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun markSent(operation: PendingMemo): Boolean =
        writableDatabase.update("memo_outbox", ContentValues().apply {
            put("sent", MemoJson.encode(operation.desired)); put("sent_deleted", if (operation.deleted) 1 else 0)
        }, "name = ? AND revision = ?", arrayOf(operation.desired.name, operation.revision.toString())) != 0

    fun markIssue(name: String, status: String, server: Memo?) {
        writableDatabase.update("memo_outbox", ContentValues().apply {
            put("status", status); put("server", server?.let(MemoJson::encode))
        }, "name = ?", arrayOf(name))
    }

    fun acknowledge(operation: PendingMemo, server: Memo?) {
        writableDatabase.transaction {
            val current = pending().firstOrNull { it.desired.name == operation.desired.name } ?: return@transaction
            // The fence and the ACK baseline are one transaction. A newer local revision keeps
            // its desired view, while this server result remains durable protection from stale
            // list pages during the next reconciliation.
            touch(operation.desired.name)
            upsertFence(operation.desired.name, server)
            putMetadata("last_upload_ack", Instant.now().toString())
            if (current.revision == operation.revision) {
                delete("memo_outbox", "name = ?", arrayOf(operation.desired.name))
                if (server == null) delete("feed_memos", "name = ?", arrayOf(operation.desired.name)) else upsertMemo(server)
            } else if (server != null) {
                // A newer local edit stays visible; only advance its confirmed baseline.
                update("memo_outbox", ContentValues().apply {
                    put("base", MemoJson.encode(server)); putNull("sent"); put("sent_deleted", 0)
                    put("status", MemoSyncStatus.PENDING)
                }, "name = ?", arrayOf(operation.desired.name))
            } else {
                markIssue(operation.desired.name, MemoSyncStatus.CONFLICT, null)
            }
        }
    }

    fun resolve(name: String, copy: Memo?) {
        writableDatabase.transaction {
            touch(name)
            val operation = pending().first { it.desired.name == name }
            require(operation.status == MemoSyncStatus.CONFLICT)
            delete("memo_outbox", "name = ?", arrayOf(name))
            delete("feed_memos", "name = ?", arrayOf(name))
            operation.server?.let { upsertMemo(it) }
            if (copy != null) queue(copy, null)
        }
    }

    fun retry(name: String): Boolean {
        val operation = pending().firstOrNull { it.desired.name == name } ?: return false
        if (operation.status != MemoSyncStatus.FAILED) return false
        return writableDatabase.transaction {
            touch(name)
            val values = ContentValues().apply {
                put("status", MemoSyncStatus.PENDING)
                // An unresolved create is never resent automatically. This explicit retry starts a new attempt.
                if (operation.base == null) {
                    putNull("sent")
                    put("sent_deleted", 0)
                }
            }
            update("memo_outbox", values, "name = ? AND status = ?",
                arrayOf(name, MemoSyncStatus.FAILED)) != 0
        }
    }

    // Complete scans are applied atomically; partial callers retain clean rows and spaces.
    /** Compatibility overload for callers that do not participate in fence-aware scans. */
    fun applyScan(memos: List<Memo>, spaces: List<Space>, byteLimit: Long,
        incomplete: Boolean, pruneCandidates: Set<String>?) {
        applyScan(memos, spaces, byteLimit, incomplete, pruneCandidates, null, emptyMap())
    }

    /**
     * Applies only a complete scan. A scan snapshot is optional for old callers, but when
     * present it gives ACK fences and local touches precedence over raw list data.
     * [verifiedFences] contains entries proven by a matching list item or authoritative GET;
     * null is meaningful and represents an authoritative deletion.
     */
    fun applyScan(memos: List<Memo>, spaces: List<Space>, byteLimit: Long = 100L * 1024 * 1024,
        incomplete: Boolean = false, pruneCandidates: Set<String>? = null,
        scan: ScanSnapshot? = null, verifiedFences: Map<String, Memo?> = emptyMap()) {
        val inventoryComplete = scan?.inventoryComplete == true || !incomplete
        writableDatabase.transaction {
            val pendingNames = pending().map { it.desired.name }.toSet()
            val touchedNames = scan?.let { touchedSince(it.touchGeneration) }.orEmpty()
            val currentFences = fences().associateBy { it.name }
            val capturedFences = scan?.fences?.associateBy { it.name }.orEmpty()
            // Cache quota omission is distinct from an incomplete remote inventory. A complete
            // inventory may replace/prune clean rows even when only a bounded subset fits cache.
            val cleanExisting = when {
                !inventoryComplete -> feed().filter { it.name !in pendingNames && it.name !in touchedNames }
                pruneCandidates == null -> emptyList()
                else -> feed().filter { it.name !in pruneCandidates }
            }
            val accepted = linkedMapOf<String, Memo>()
            (cleanExisting + memos).forEach { memo ->
                if (memo.name in pendingNames || memo.name in touchedNames) return@forEach
                val fence = currentFences[memo.name]
                val captured = capturedFences[memo.name]
                if (fence != null) {
                    val verified = verifiedFences.containsKey(memo.name) &&
                        captured?.generation == fence.generation && memo.name !in touchedNames
                    if (!verified) {
                        if (!fence.deleted && fence.snapshot != null) accepted[memo.name] = fence.snapshot
                        return@forEach
                    }
                    // A verified replacement is authoritative and may be newer than the ACK.
                    if (verifiedFences[memo.name] != null) accepted[memo.name] = verifiedFences.getValue(memo.name)!!
                    return@forEach
                }
                accepted[memo.name] = memo
            }
            verifiedFences.forEach { (name, memo) ->
                if (name !in pendingNames && name !in touchedNames &&
                    capturedFences[name]?.generation == currentFences[name]?.generation) {
                    if (memo != null) accepted[name] = memo else accepted.remove(name)
                }
            }
            val protected = pendingNames.mapNotNull { name -> feed().firstOrNull { it.name == name } }
            val retained = CacheBudget.retain(accepted.values.toList(), byteLimit, protected)
            if (inventoryComplete) {
                feed().filter { it.name !in pendingNames && it.name !in touchedNames }
                    .forEach { delete("feed_memos", "name = ?", arrayOf(it.name)) }
            }
            retained.forEach { upsertMemo(it) }

            if (scan != null && inventoryComplete) {
                val afterTouches = touchedSince(scan.touchGeneration)
                capturedFences.forEach { (name, captured) ->
                    val current = fences().firstOrNull { it.name == name }
                    if (current?.generation == captured.generation && name !in afterTouches &&
                        name !in pendingNames && verifiedFences.containsKey(name)) {
                        delete("memo_sync_fences", "name = ?", arrayOf(name))
                    }
                }
            }
            if (inventoryComplete) {
                delete("offline_spaces", null, null)
                spaces.forEach { space -> insertOrThrow("offline_spaces", null, ContentValues().apply {
                    put("name", space.name); put("title", space.title); put("description", space.description)
                }) }
                setMetadata("spaces_known", "true")
                setMetadata("last_sync", Instant.now().toString())
            }
            setMetadata("incomplete", (incomplete || retained.size < accepted.size).toString())
        }
    }

    fun reminderRecords(): List<ReminderRecord> = readableDatabase.rawQuery(
        "SELECT memo_name, due_key, content FROM reminders", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ReminderRecord(cursor.getString(0), Instant.parse(cursor.getString(1)), cursor.getString(2)))
    } }

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
            delete("memo_outbox", null, null)
            delete("memo_sync_fences", null, null)
            delete("memo_sync_touches", null, null)
            delete("offline_spaces", null, null)
            delete("sync_metadata", null, null)
        }
    }

    private fun SQLiteDatabase.touch(name: String) {
        val next = (queryMetadata(TOUCH_GENERATION)?.toLongOrNull() ?: 0L) + 1L
        putMetadata(TOUCH_GENERATION, next.toString())
        insertWithOnConflict("memo_sync_touches", null, ContentValues().apply {
            put("name", name); put("generation", next)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SQLiteDatabase.upsertFence(name: String, server: Memo?) {
        val next = (queryMetadata(FENCE_GENERATION)?.toLongOrNull() ?: 0L) + 1L
        putMetadata(FENCE_GENERATION, next.toString())
        insertWithOnConflict("memo_sync_fences", null, ContentValues().apply {
            put("name", name); put("generation", next)
            server?.let { put("snapshot", MemoJson.encode(it)) } ?: putNull("snapshot")
            put("deleted", if (server == null) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SQLiteDatabase.queryMetadata(key: String): String? = rawQuery(
        "SELECT value FROM sync_metadata WHERE key = ?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SQLiteDatabase.putMetadata(key: String, value: String) {
        insertWithOnConflict("sync_metadata", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SQLiteDatabase.touchedSince(generation: Long): Set<String> = rawQuery(
        "SELECT name FROM memo_sync_touches WHERE generation > ?", arrayOf(generation.toString()),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun Memo.toValues() = ContentValues().apply {
        put("snapshot", MemoJson.encode(this@toValues))
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
        const val TOUCH_GENERATION = "memo_touch_generation"
        const val FENCE_GENERATION = "memo_fence_generation"
    }
}
