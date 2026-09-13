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
import java.time.Instant
import java.util.UUID

class MemoRepository(
    private val credentials: CredentialStore,
    private val database: AppDatabase,
    private val api: MemosApi,
    private val reminders: ReminderCoordinator,
    private val syncScheduler: SyncScheduler,
) {
    // Network work never holds the local-write lock. Account replacement shares the sync lock.
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var currentAccount = credentials.load()
    private var feedState = "NORMAL"
    private var feedSpace: String? = null
    private val mutableFeed = MutableStateFlow<List<Memo>>(emptyList())
    val feed: StateFlow<List<Memo>> = mutableFeed
    private val mutableAccount = MutableStateFlow(currentAccount?.summary())
    val accountSummary: StateFlow<AccountSummary?> = mutableAccount
    private val mutableSpaces = MutableStateFlow<List<Space>?>(
        if (database.metadata("spaces_known") == "true") database.spaces() else null,
    )
    val spaces: StateFlow<List<Space>?> = mutableSpaces
    val hasMore = MutableStateFlow(false)
    val syncState = MutableStateFlow(
        SyncState(
            incomplete = database.metadata("incomplete") == "true",
            signInRequired = database.metadata("auth_required") == "true",
            lastSyncedAt = lastSyncedAt(),
        ),
    )
    val syncIssues = MutableStateFlow<List<PendingMemo>>(emptyList())

    init {
        currentAccount?.let {
            if (database.metadata("owner") == null) database.setMetadata("owner", owner(it))
        }
        publishLocal()
    }

    fun account(): Account? = currentAccount
    fun selectedSpace(): String? = credentials.selectedSpace()
    fun saveSelectedSpace(name: String?) = credentials.saveSelectedSpace(name)
    fun scheduleSync() = syncScheduler.enqueueRepair()
    private fun owner(account: Account) = account.baseUrl + "\n" + account.userName
    private fun lastSyncedAt(): Instant? = database.metadata("last_sync")?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
    private suspend fun <T> networkLocked(block: suspend () -> T): T = syncMutex.withLock { withContext(Dispatchers.IO) { block() } }

    suspend fun signInOptions(baseUrl: String): SignInOptions = withContext(Dispatchers.IO) { api.signInOptions(baseUrl) }
    suspend fun signInWithPassword(baseUrl: String, username: String, password: String): Account = networkLocked {
        activateSession(baseUrl, api.signInWithPassword(baseUrl, username, password))
    }
    suspend fun beginSso(baseUrl: String, provider: AuthProvider): String = locked {
        OAuthFlow.start(baseUrl, provider).also { credentials.savePendingOAuth(it.pending) }.authorizationUrl
    }
    suspend fun completeSso(callbackUrl: String): Account = networkLocked {
        val pending = credentials.pendingOAuth() ?: throw AppException(AppError.SignInFailed)
        val code = OAuthFlow.authorizationCode(callbackUrl, pending)
        credentials.clearPendingOAuth()
        activateSession(pending.baseUrl, api.signInWithSso(pending.baseUrl, pending.providerName, code, pending.codeVerifier))
    }
    suspend fun configure(baseUrl: String, token: String): Account = networkLocked {
        val user = api.currentUser(baseUrl, token)
        activate(Account(baseUrl, token, user.name, user.displayName.ifBlank { user.username },
            api.supportsMemoReminderTime(baseUrl, token)))
    }

    /** Scope-independent sync used by WorkManager, never by a blocking UI operation. */
    suspend fun refresh(): Account? = networkLocked {
        val stored = currentAccount ?: return@networkLocked null
        if (syncState.value.signInRequired) throw AppException(AppError.Authentication)
        syncState.value = syncState.value.copy(syncing = true, failed = false)
        try {
            val account = ensureValidSession(stored)
            try {
                synchronize(account)
            } catch (error: AppException) {
                if (error.error != AppError.Authentication || account.authMethod != AuthMethod.SESSION) throw error
                // A rejected access token does not prove the session is lost. Rotate the refresh
                // token once and retry; only rejection of that refresh requires user interaction.
                synchronize(refreshSession(account))
            }
        } catch (error: AppException) {
            if (error.error == AppError.Authentication) locked { database.setMetadata("auth_required", "true") }
            syncState.value = syncState.value.copy(
                signInRequired = error.error == AppError.Authentication,
                failed = error.error != AppError.Authentication,
            )
            throw error
        } finally {
            syncState.value = syncState.value.copy(syncing = false)
        }
    }

    private suspend fun synchronize(startingAccount: Account): Account {
        var account = startingAccount
        val supported = api.supportsMemoReminderTime(account.baseUrl, account.token)
        account = account.withCapability(supported)
        currentAccount = account
        credentials.save(account)
        mutableAccount.value = account.summary()
        if (!supported) locked { reminders.clearReminders() }
        syncState.value = syncState.value.copy(signInRequired = false)
        // Pull before upload: an eventually consistent list response can never overwrite a
        // mutation acknowledged later in this same sync or resurrect a confirmed deletion.
        val pruneCandidates = locked {
            database.localMemos().filter { it.syncStatus == MemoSyncStatus.SYNCED }.map { it.name }.toSet()
        }
        val spaces = api.listSpaces(account)
        val all = linkedMapOf<String, Memo>()
        val remoteReminders = linkedMapOf<String, ReminderRecord>()
        val protected = locked { database.pending().map { it.desired } }
        val protectedNames = protected.map { it.name }.toSet()
        var incomplete = false
        for (space in listOf<String?>(null) + spaces.map { it.name }) {
            for (state in listOf("NORMAL", "ARCHIVED")) {
                val tokens = mutableSetOf<String>()
                var token: String? = null
                do {
                    val page = api.listPage(account, state, token, space, pageSize = 1_000)
                    page.memos.filter { it.state == state &&
                        (if (space == null) it.creator == account.userName else it.space == space)
                    }.forEach { memo ->
                        if (memo.creator == account.userName && memo.state == "NORMAL") memo.reminderTime?.let {
                            remoteReminders[memo.name] = ReminderRecord(memo.name, it, memo.snippet.ifBlank { memo.content })
                        }
                        if (memo.name !in protectedNames) all[memo.name] = memo
                    }
                    val retained = CacheBudget.retain(all.values.toList(), 100L * 1024 * 1024, protected)
                    if (retained.size < all.size) incomplete = true
                    all.clear(); retained.forEach { all[it.name] = it }
                    token = page.nextPageToken
                    if (token != null && !tokens.add(token)) throw AppException(AppError.InvalidResponse)
                } while (token != null)
            }
        }
        locked {
            database.applyScan(all.values.toList(), spaces, incomplete = incomplete, pruneCandidates = pruneCandidates)
            mutableSpaces.value = database.spaces()
            reconcileLocalReminders(account, remoteReminders.values.toList())
            publishLocal()
        }
        val flushResult = flush(account)
        if (flushResult.retryableFailure) throw AppException(AppError.Network)
        if (flushResult.moreWork) syncScheduler.enqueueRepair()
        reminders.syncSucceeded()
        return account
    }

    private data class FlushResult(val retryableFailure: Boolean, val moreWork: Boolean)

    private suspend fun flush(account: Account): FlushResult {
        val names = locked { database.pending().map { it.desired.name } }
        var retryableFailure = false
        var moreWork = false
        for (name in names) {
            var attempts = 0
            while (attempts++ < MAX_UPLOAD_STEPS_PER_MEMO) {
                val operation = locked { database.pending().firstOrNull { it.desired.name == name } } ?: break
                if (operation.status != MemoSyncStatus.PENDING) break
                try {
                    upload(account, operation)
                } catch (error: AppException) {
                    if (error.error == AppError.Authentication) throw error
                    // A timeout leaves the frozen dispatched intent available for GET-based recovery.
                    val retryable = error.error == AppError.Network ||
                        error.error is AppError.Server && (error.error.status == 429 || error.error.status >= 500)
                    if (retryable) {
                        retryableFailure = true
                        syncState.value = syncState.value.copy(failed = true)
                    } else {
                        locked { database.markIssue(name, MemoSyncStatus.FAILED, null); publishLocal() }
                    }
                    break
                }
            }
            if (locked { database.pending().any { it.desired.name == name && it.status == MemoSyncStatus.PENDING } }) {
                moreWork = true
            }
        }
        return FlushResult(retryableFailure, moreWork)
    }

    private fun getRemote(account: Account, name: String): Memo? = try { api.getMemo(account, name) }
    catch (error: AppException) { if (error.error == AppError.Server(404)) null else throw error }

    private suspend fun upload(account: Account, operation: PendingMemo) {
        val name = operation.desired.name
        val remote = getRemote(account, name)
        // Upstream CreateMemo ignores pinned/state; persist the actual create intent, then patch remaining intent.
        val intent = operation.sent ?: if (operation.base == null) operation.desired.copy(pinned = false, state = "NORMAL") else operation.desired
        val deleting = if (operation.sent != null) operation.sentDeleted else operation.deleted
        // Recovery compares against the DISPATCHED intent, not a newer local edit.
        if (operation.sent != null && ((deleting && remote == null) || (!deleting && remote?.sameDesired(intent) == true))) {
            locked {
                database.acknowledge(operation.copy(revision = if (operation.desired.sameDesired(intent) && operation.deleted == deleting)
                    operation.revision else -1), remote)
                reconcileLocalReminders(account); publishLocal()
            }
            return
        }
        if (operation.sent != null && operation.base == null && remote == null) {
            // A create left the device but its stable ID cannot be found. An incompatible server may
            // have ignored memoId, so never risk an automatic duplicate POST.
            locked { database.markIssue(name, MemoSyncStatus.FAILED, null); publishLocal() }
            return
        }
        if (operation.base == null) {
            if (remote != null) {
                locked { database.markIssue(name, MemoSyncStatus.CONFLICT, remote); publishLocal() }
                return
            }
        } else if (remote == null || !remote.sameSnapshot(operation.base)) {
            locked { database.markIssue(name, MemoSyncStatus.CONFLICT, remote); publishLocal() }
            return
        }
        if (remote != null && remote.creator != account.userName) throw AppException(AppError.Permission)
        // Freeze before the request so process death cannot turn a dispatched create into a fresh one.
        val dispatched = operation.copy(desired = intent, deleted = deleting)
        if (!locked { database.markSent(dispatched) }) return
        val result = when {
            deleting -> { api.deleteMemo(account, name); null }
            operation.base == null -> {
                try {
                    api.createMemo(account, NewMemo(intent.content, intent.reminderTime, intent.visibility,
                        intent.space), name.substringAfter('/')).memo
                } catch (error: AppException) {
                    if (error.error != AppError.Server(409)) throw error
                    getRemote(account, name) ?: throw error
                }
            }
            else -> api.applyDesired(account, requireNotNull(remote), intent)
        }
        if (result != null && !result.sameDesired(intent)) {
            locked { database.markIssue(name, MemoSyncStatus.CONFLICT, result); publishLocal() }
            return
        }
        locked {
            database.acknowledge(dispatched.copy(revision = if (
                !operation.desired.sameDesired(intent) || operation.deleted != deleting) -1 else operation.revision), result)
            reconcileLocalReminders(account); publishLocal()
        }
    }

    suspend fun page(expected: AccountSummary, archive: Boolean, more: Boolean = false, space: String? = null) = locked {
        requireLocalAccount(expected)
        feedState = if (archive) "ARCHIVED" else "NORMAL"
        feedSpace = space
        publishLocal()
    }

    suspend fun get(expected: AccountSummary, name: String): Memo = locked {
        requireLocalAccount(expected)
        database.localMemos(true).firstOrNull { it.name == name } ?: throw AppException(AppError.Server(404))
    }

    suspend fun create(expected: AccountSummary, draft: NewMemo): CreatedMemoResult = locked {
        val account = requireLocalAccount(expected)
        validate(account, draft.visibility, draft.space, draft.reminderTime != null)
        val memo = Memo(newName(), draft.content, draft.content, draft.visibility, Instant.now(), null,
            draft.reminderTime, account.userName, space = draft.space)
        database.queue(memo, null)
        localWriteFinished(account)
        CreatedMemoResult(memo.copy(syncStatus = MemoSyncStatus.PENDING), true)
    }

    suspend fun edit(expected: AccountSummary, base: Memo, edit: MemoEdit): Memo = locked {
        val account = requireLocalAccount(expected)
        val local = requireEditable(account, base)
        validate(account, edit.visibility, local.space, edit.reminderTime != local.reminderTime)
        val desired = local.copy(content = edit.content, snippet = edit.content, visibility = edit.visibility,
            reminderTime = edit.reminderTime)
        database.queue(desired, local.serverFields())
        localWriteFinished(account)
        database.localMemos().first { it.name == desired.name }
    }

    suspend fun action(expected: AccountSummary, base: Memo, action: String): Memo? = locked {
        val account = requireLocalAccount(expected)
        val local = requireEditable(account, base)
        val desired = when (action) {
            "pin" -> local.copy(pinned = !local.pinned)
            "archive" -> local.copy(state = if (local.state == "ARCHIVED") "NORMAL" else "ARCHIVED")
            "delete" -> local
            else -> throw IllegalArgumentException("Unknown action")
        }
        database.queue(desired, local.serverFields(), action == "delete")
        if (action == "delete") reminders.memoDeleted(desired.name)
        localWriteFinished(account)
        if (action == "delete") null else database.localMemos().first { it.name == desired.name }
    }

    suspend fun resolveConflict(expected: AccountSummary, name: String, preserveAsNew: Boolean) = locked {
        val account = requireLocalAccount(expected)
        val operation = database.pending().first { it.desired.name == name }
        require(operation.status == MemoSyncStatus.CONFLICT)
        val copy = if (preserveAsNew) operation.desired.copy(name = newName(), creator = account.userName,
            createTime = Instant.now(), updateTime = null, syncStatus = MemoSyncStatus.PENDING) else null
        database.resolve(name, copy)
        if (operation.server == null) reminders.memoDeleted(name)
        localWriteFinished(account)
    }

    suspend fun retryFailed(expected: AccountSummary, name: String) = locked {
        val account = requireLocalAccount(expected)
        if (!database.retry(name)) throw AppException(AppError.Conflict)
        localWriteFinished(account)
    }

    private fun newName() = "memos/mp-" + UUID.randomUUID().toString().replace("-", "")
    private fun validate(account: Account, visibility: String, space: String?, reminderChanged: Boolean) {
        require(visibility in listOf("PRIVATE", "PROTECTED", "PUBLIC") || (visibility == "SPACE" && space != null))
        space?.let { require(Regex("^spaces/[^/]+$").matches(it)) }
        if (reminderChanged && !account.supportsMemoReminderTime) throw AppException(AppError.UnsupportedReminder)
    }
    private fun requireEditable(account: Account, base: Memo): Memo {
        val local = database.localMemos().firstOrNull { it.name == base.name } ?: throw AppException(AppError.Conflict)
        if (local.creator != account.userName) throw AppException(AppError.Permission)
        // A server acknowledgement can change timestamps/snippets while an editor stays open.
        if (!local.sameDesired(base)) throw AppException(AppError.Conflict)
        if (local.syncStatus == MemoSyncStatus.CONFLICT) throw AppException(AppError.Conflict)
        return local
    }
    private fun requireLocalAccount(expected: AccountSummary): Account {
        val current = currentAccount ?: throw AppException(AppError.Authentication)
        val persisted = credentials.load() ?: throw AppException(AppError.Authentication)
        if (current.baseUrl != expected.baseUrl || current.userName != expected.userName ||
            persisted.baseUrl != expected.baseUrl || persisted.userName != expected.userName ||
            database.metadata("owner") != owner(current)) throw AppException(AppError.Authentication)
        return current // Access-token expiry cannot prevent a local save.
    }
    private fun localWriteFinished(account: Account) {
        reconcileLocalReminders(account)
        publishLocal()
        syncScheduler.enqueueRepair()
    }
    private fun reconcileLocalReminders(account: Account, remote: List<ReminderRecord>? = null) {
        if (!account.supportsMemoReminderTime) { reminders.clearReminders(); return }
        val records = (remote ?: database.reminderRecords()).associateBy { it.memoName }.toMutableMap()
        database.localMemos(true).forEach { memo ->
            records.remove(memo.name)
            if (memo.state == "NORMAL" && memo.creator == account.userName) memo.reminderTime?.let {
                records[memo.name] = ReminderRecord(memo.name, it, memo.snippet.ifBlank { memo.content })
            }
        }
        database.pending().filter { it.deleted }.forEach { records.remove(it.desired.name) }
        reminders.reconcile(records.values.toList())
    }
    private fun publishLocal() {
        val account = currentAccount
        val allowed = account != null && database.metadata("owner") == owner(account)
        val memos = if (allowed) database.localMemos() else emptyList()
        mutableFeed.value = memos.filter { it.state == feedState &&
            (if (feedSpace == null) it.creator == account?.userName else it.space == feedSpace) }
        syncIssues.value = if (allowed) database.pending() else emptyList()
        syncState.value = syncState.value.copy(
            pending = syncIssues.value.size,
            incomplete = database.metadata("incomplete") == "true",
            lastSyncedAt = lastSyncedAt(),
        )
    }

    private suspend fun activateSession(baseUrl: String, session: SignedInSession): Account {
        val tokens = session.tokens
        val account = Account(baseUrl, tokens.accessToken, session.user.name,
            session.user.displayName.ifBlank { session.user.username.ifBlank { session.user.name } },
            currentAccount?.takeIf { it.baseUrl == baseUrl && it.userName == session.user.name }?.supportsMemoReminderTime ?: false,
            AuthMethod.SESSION, tokens.refreshToken, tokens.accessTokenExpiresAt)
        return activate(account)
    }
    private suspend fun activate(account: Account): Account = locked {
        val previousOwner = database.metadata("owner")
        if (previousOwner != null && previousOwner != owner(account)) {
            if (database.pending().isNotEmpty()) throw AppException(AppError.PendingAccount)
            reminders.clearReminders(); reminders.clearDiagnostics(); database.clearAll()
            credentials.saveSelectedSpace(null); feedSpace = null; feedState = "NORMAL"
        }
        database.setMetadata("owner", owner(account))
        database.setMetadata("auth_required", "false")
        credentials.save(account)
        currentAccount = account
        mutableAccount.value = account.summary()
        mutableSpaces.value = if (database.metadata("spaces_known") == "true") database.spaces() else null
        syncState.value = syncState.value.copy(signInRequired = false)
        publishLocal()
        syncScheduler.ensurePeriodicSync(); syncScheduler.enqueueRepair()
        account
    }
    private fun ensureValidSession(account: Account): Account {
        if (account.authMethod != AuthMethod.SESSION) return account
        if (account.token.isNotBlank() && account.accessTokenExpiresAt?.isAfter(Instant.now().plusSeconds(30)) == true) return account
        return refreshSession(account)
    }

    private fun refreshSession(account: Account): Account {
        val refreshToken = account.refreshToken ?: throw AppException(AppError.Authentication)
        val tokens = api.refreshSession(account.baseUrl, refreshToken)
        return Account(account.baseUrl, tokens.accessToken, account.userName, account.displayName,
            account.supportsMemoReminderTime, AuthMethod.SESSION, tokens.refreshToken, tokens.accessTokenExpiresAt).also {
            currentAccount = it
            credentials.save(it)
        }
    }
    private fun Account.withCapability(supported: Boolean) = Account(baseUrl, token, userName, displayName,
        supported, authMethod, refreshToken, accessTokenExpiresAt)

    private companion object {
        const val MAX_UPLOAD_STEPS_PER_MEMO = 8
    }

    suspend fun logout() = networkLocked {
        val account = currentAccount
        if (account?.authMethod == AuthMethod.SESSION) runCatching { api.signOut(ensureValidSession(account)) }
        locked {
            syncScheduler.cancel(); reminders.clearReminders(); reminders.clearDiagnostics()
            currentAccount = null; credentials.clear(); database.clearAll()
            mutableAccount.value = null; mutableSpaces.value = emptyList()
            feedState = "NORMAL"; feedSpace = null; syncState.value = SyncState(); publishLocal()
        }
    }
}
