package com.vstokke.memos.domain

import java.time.Instant

class Account(
    val baseUrl: String,
    val token: String,
    val userName: String,
    val displayName: String,
    val supportsMemoReminderTime: Boolean = false,
) {
    fun summary(): AccountSummary = AccountSummary(baseUrl, userName, displayName, supportsMemoReminderTime)

    override fun toString(): String =
        "Account(baseUrl=$baseUrl, token=[redacted], userName=$userName, displayName=$displayName)"
}

data class AccountSummary(
    val baseUrl: String,
    val userName: String,
    val displayName: String,
    val supportsMemoReminderTime: Boolean,
)

data class User(
    val name: String,
    val username: String,
    val displayName: String,
)

data class Memo(
    val name: String,
    val content: String,
    val snippet: String,
    val visibility: String,
    val createTime: Instant,
    val updateTime: Instant?,
    val reminderTime: Instant?,
    val creator: String = "",
    val state: String = "NORMAL",
    val pinned: Boolean = false,
    val parent: String? = null,
    val space: String? = null,
)

data class MemoPage(val memos: List<Memo>, val nextPageToken: String?)

data class MemoEdit(val content: String, val visibility: String, val reminderTime: Instant?)

data class ReminderRecord(
    val memoName: String,
    val dueAt: Instant,
    val content: String,
)

data class NewMemo(
    val content: String,
    val reminderTime: Instant?,
    val visibility: String = "PRIVATE",
)

data class CreatedMemoResult(
    val memo: Memo,
    val reminderPreserved: Boolean,
)

sealed class AppError {
    data object Authentication : AppError()
    data object Network : AppError()
    data object Conflict : AppError()
    data object Permission : AppError()
    data class Server(val status: Int) : AppError()
    data object InvalidResponse : AppError()
    data object UnsupportedReminder : AppError()

    fun userMessage(): String = when (this) {
        Conflict -> "This memo changed on the server. Your draft is kept. Reopen the memo to load the server version before editing again."
        Permission -> "You do not have permission for this action."
        Authentication -> "The server rejected your access token."
        Network -> "Could not reach the Memos server. Check your connection and address."
        is Server -> "The Memos server returned an error (HTTP $status)."
        InvalidResponse -> "The server returned a response Memos Pocket could not read."
        UnsupportedReminder -> "Synced reminders are not available on this Memos server."
    }
}

class AppException(val error: AppError) : Exception()
