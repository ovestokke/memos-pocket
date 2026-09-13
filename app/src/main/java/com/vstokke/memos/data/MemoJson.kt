package com.vstokke.memos.data

import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoSyncStatus
import org.json.JSONObject
import java.time.Instant

// Lossless server snapshots: SQLite millisecond columns alone lose revision precision.
internal object MemoJson {
    fun encode(memo: Memo): String = JSONObject().apply {
        put("name", memo.name); put("content", memo.content); put("snippet", memo.snippet)
        put("visibility", memo.visibility); put("creator", memo.creator); put("state", memo.state)
        put("pinned", memo.pinned); put("parent", memo.parent); put("space", memo.space)
        put("createTime", memo.createTime.toString()); put("updateTime", memo.updateTime?.toString())
        put("reminderTime", memo.reminderTime?.toString())
    }.toString()

    fun decode(json: String): Memo = JSONObject(json).let {
        fun optional(key: String) = if (it.isNull(key)) null else it.optString(key).ifBlank { null }
        Memo(it.getString("name"), it.getString("content"), it.getString("snippet"), it.getString("visibility"),
            Instant.parse(it.getString("createTime")), optional("updateTime")?.let(Instant::parse),
            optional("reminderTime")?.let(Instant::parse), it.getString("creator"), it.getString("state"),
            it.getBoolean("pinned"), optional("parent"), optional("space"))
    }
}

internal fun Memo.serverFields() = copy(syncStatus = MemoSyncStatus.SYNCED)

internal fun Memo.sameDesired(other: Memo): Boolean =
    name == other.name && creator == other.creator && content == other.content &&
        visibility == other.visibility && reminderTime == other.reminderTime && pinned == other.pinned &&
        state == other.state && parent == other.parent && space == other.space

internal fun Memo.sameSnapshot(other: Memo): Boolean = serverFields() == other.serverFields()

// Stored per memo, with a frozen dispatched intent for recovery after process death.
data class PendingMemo(
    val desired: Memo,
    val base: Memo?,
    val deleted: Boolean,
    val revision: Long,
    val sent: Memo?,
    val sentDeleted: Boolean,
    val status: String,
    val server: Memo?,
)
