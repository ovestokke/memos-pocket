package com.vstokke.memos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vstokke.memos.domain.*
import com.vstokke.memos.reminders.ReminderCoordinator
import com.vstokke.memos.work.SyncScheduler

class MemoRepository(
    private val credentials: CredentialStore,
    private val database: AppDatabase,
    private val api: MemosApi,
    private val reminders: ReminderCoordinator,
    private val syncScheduler: SyncScheduler,
) {
    private val mutex = Mutex()
    private val mutableFeed = MutableStateFlow(database.feed().filter { it.state == "NORMAL" })
    val feed: StateFlow<List<Memo>> = mutableFeed
    private val mutableAccount = MutableStateFlow(credentials.load()?.summary())
    val accountSummary: StateFlow<AccountSummary?> = mutableAccount
    private var feedState = "NORMAL"
    private var nextPage: String? = null
    private val seenPages = mutableSetOf<String>()
    val hasMore = MutableStateFlow(false)

    fun account(): Account? = credentials.load()

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    suspend fun configure(baseUrl: String, token: String): Account = locked {
        val user = api.currentUser(baseUrl, token)
        val account = Account(baseUrl, token, user.name,
            user.displayName.ifBlank { user.username.ifBlank { user.name } },
            api.supportsMemoReminderTime(baseUrl, token))
        val previous = credentials.load()
        if (previous?.baseUrl != account.baseUrl || previous.userName != account.userName) {
            reminders.clearReminders()
            reminders.clearDiagnostics()
            database.clearAll()
            mutableFeed.value = emptyList()
            resetPages()
        }
        credentials.save(account)
        mutableAccount.value = account.summary()
        if (!account.supportsMemoReminderTime) reminders.clearReminders()
        syncScheduler.ensurePeriodicSync()
        account
    }

    suspend fun refresh(): Account? = locked {
        val stored = credentials.load() ?: return@locked null
        // A failed profile request leaves the last known capability intact; it is not evidence of absence.
        val supported = api.supportsMemoReminderTime(stored.baseUrl, stored.token)
        val account = Account(stored.baseUrl, stored.token, stored.userName, stored.displayName, supported)
        credentials.save(account)
        mutableAccount.value = account.summary()
        // Complete cleanup before any feed request can fail. Do not cancel the executing worker.
        if (!supported) reminders.clearReminders()
        val page = api.listPage(account, feedState)
        if (supported) reminders.reconcile(api.listAllReminders(account))
        resetPages()
        nextPage = page.nextPageToken
        hasMore.value = nextPage != null
        publishFeed(page.memos)
        reminders.syncSucceeded()
        account
    }

    suspend fun page(expected: AccountSummary, archive: Boolean, more: Boolean = false) = locked {
        val account = requireAccount(expected)
        val target = if (archive) "ARCHIVED" else "NORMAL"
        val token = if (more && target == feedState) nextPage ?: return@locked else null
        if (token != null && token in seenPages) throw AppException(AppError.InvalidResponse)
        val page = api.listPage(account, target, token)
        if (page.nextPageToken != null && (page.nextPageToken == token || page.nextPageToken in seenPages)) {
            throw AppException(AppError.InvalidResponse)
        }
        if (token == null) resetPages() else seenPages += token
        feedState = target
        nextPage = page.nextPageToken
        hasMore.value = nextPage != null
        publishFeed((if (token == null) emptyList() else mutableFeed.value) + page.memos)
    }

    suspend fun get(expected: AccountSummary, name: String): Memo = locked { api.getMemo(requireAccount(expected), name) }

    suspend fun create(expected: AccountSummary, draft: NewMemo): CreatedMemoResult = locked {
        val account = requireAccount(expected)
        val result = api.createMemo(account, draft)
        acceptMemo(account, result.memo)
        result
    }

    suspend fun edit(expected: AccountSummary, base: Memo, edit: MemoEdit): Memo = locked {
        val account = requireAccount(expected)
        val fresh = freshForWrite(account, base)
        val result = api.editMemo(account, fresh, edit)
        acceptMemo(account, result)
        result
    }

    suspend fun action(expected: AccountSummary, base: Memo, action: String): Memo? = locked {
        val account = requireAccount(expected)
        val fresh = freshForWrite(account, base)
        val result = when (action) {
            "pin" -> api.setPinned(account, fresh, !fresh.pinned)
            "archive" -> api.setState(account, fresh, if (fresh.state == "ARCHIVED") "NORMAL" else "ARCHIVED")
            "delete" -> {
                api.deleteMemo(account, fresh.name)
                reminders.memoDeleted(fresh.name)
                publishFeed(mutableFeed.value.filterNot { it.name == fresh.name })
                null
            }
            else -> throw IllegalArgumentException("Unknown action")
        }
        result?.let { acceptMemo(account, it) }
        result
    }

    private fun freshForWrite(account: Account, base: Memo): Memo {
        val fresh = api.getMemo(account, base.name)
        if (fresh.creator != account.userName) throw AppException(AppError.Permission)
        // Best-effort detection, not an atomic conditional write: the API has no revision precondition.
        if (fresh != base) throw AppException(AppError.Conflict)
        return fresh
    }

    private fun acceptMemo(account: Account, memo: Memo) {
        publishFeed(mutableFeed.value.filterNot { it.name == memo.name } + if (memo.state == feedState) listOf(memo) else emptyList())
        if (account.supportsMemoReminderTime) {
            reminders.memoChanged(memo)
            syncScheduler.enqueueRepair()
        } else reminders.memoDeleted(memo.name)
    }

    private fun publishFeed(memos: List<Memo>) {
        val sorted = memos.distinctBy { it.name }.sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.createTime })
        if (feedState == "NORMAL") database.replaceFeed(sorted)
        mutableFeed.value = sorted
    }

    private fun requireAccount(expected: AccountSummary): Account {
        val current = credentials.load() ?: throw AppException(AppError.Authentication)
        if (current.baseUrl != expected.baseUrl || current.userName != expected.userName) throw AppException(AppError.Authentication)
        return current
    }

    private fun resetPages() { nextPage = null; seenPages.clear(); hasMore.value = false }

    suspend fun logout() = locked {
        syncScheduler.cancel()
        reminders.clearReminders()
        reminders.clearDiagnostics()
        credentials.clear()
        database.clearAll()
        mutableFeed.value = emptyList()
        mutableAccount.value = null
        feedState = "NORMAL"
        resetPages()
    }
}
