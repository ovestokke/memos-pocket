package com.vstokke.memos.domain

import java.time.Instant

enum class AuthMethod { PERSONAL_ACCESS_TOKEN, SESSION }

class Account(
    val baseUrl: String,
    val token: String,
    val userName: String,
    val displayName: String,
    val supportsMemoReminderTime: Boolean = false,
    val authMethod: AuthMethod = AuthMethod.PERSONAL_ACCESS_TOKEN,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Instant? = null,
) {
    fun summary(): AccountSummary = AccountSummary(baseUrl, userName, displayName, supportsMemoReminderTime, authMethod)

    override fun toString(): String =
        "Account(baseUrl=$baseUrl, token=[redacted], refreshToken=[redacted], userName=$userName, " +
            "displayName=$displayName, authMethod=$authMethod)"
}

data class AccountSummary(
    val baseUrl: String,
    val userName: String,
    val displayName: String,
    val supportsMemoReminderTime: Boolean,
    val authMethod: AuthMethod = AuthMethod.PERSONAL_ACCESS_TOKEN,
)

data class AuthProvider(
    val name: String,
    val title: String,
    val clientId: String,
    val authorizationUrl: String,
    val scopes: List<String>,
)

data class SignInOptions(
    val passwordAllowed: Boolean,
    val providers: List<AuthProvider>,
)

data class SessionTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
)

data class SignedInSession(
    val user: User,
    val tokens: SessionTokens,
)

data class PendingOAuth(
    val baseUrl: String,
    val providerName: String,
    val state: String,
    val codeVerifier: String,
    val createdAtEpochMillis: Long,
)

data class OAuthStart(
    val authorizationUrl: String,
    val pending: PendingOAuth,
)

data class User(
    val name: String,
    val username: String,
    val displayName: String,
)

object MemoSyncStatus {
    const val SYNCED = "Synced"
    const val PENDING = "Waiting to sync"
    const val CONFLICT = "Conflict"
    const val FAILED = "Sync failed"
}

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
    val syncStatus: String = MemoSyncStatus.SYNCED,
)

data class SyncState(
    val syncing: Boolean = false,
    val signInRequired: Boolean = false,
    val failed: Boolean = false,
    val incomplete: Boolean = false,
    val pending: Int = 0,
    val lastSyncedAt: Instant? = null,
)

data class MemoPage(val memos: List<Memo>, val nextPageToken: String?)

data class Space(
    val name: String,
    val title: String,
    val description: String,
)

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
    val space: String? = null,
)

data class CreatedMemoResult(
    val memo: Memo,
    val reminderPreserved: Boolean,
)

sealed class AppError {
    data object Authentication : AppError()
    data object InvalidCredentials : AppError()
    data object SignInFailed : AppError()
    data object Network : AppError()
    data object Conflict : AppError()
    data object Permission : AppError()
    data class Server(val status: Int) : AppError()
    data object InvalidResponse : AppError()
    data object UnsupportedReminder : AppError()
    data object PendingAccount : AppError()

    fun userMessage(): String = when (this) {
        Conflict -> "This memo changed on the server. Your draft is kept. Reopen the memo to load the server version before editing again."
        Permission -> "You do not have permission for this action."
        Authentication -> "Your session is no longer valid. Sign in again."
        InvalidCredentials -> "The username or password was not accepted."
        SignInFailed -> "Sign-in could not be completed. Try again."
        Network -> "Could not reach the Memos server. Check your connection and address."
        is Server -> "The Memos server returned an error (HTTP $status)."
        InvalidResponse -> "The server returned a response Memos Pocket could not read."
        UnsupportedReminder -> "Synced reminders are not available on this Memos server."
        PendingAccount -> "This device has unsynced changes for another account. Sign in to that account first."
    }
}

class AppException(val error: AppError) : Exception()
