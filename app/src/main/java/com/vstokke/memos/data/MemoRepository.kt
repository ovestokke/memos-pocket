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

data class SyncTurnResult(
    val account: Account?,
    val moreWork: Boolean = false,
    val pushProgress: Boolean = false,
    val pullCompleted: Boolean = false,
    val pullProgress: Boolean = false,
    val pushError: AppError? = null,
    val pullError: AppError? = null,
)

class MemoRepository(
    private val credentials: CredentialStore,
    private val database: AppDatabase,
    private val api: MemosApi,
    private val reminders: ReminderCoordinator,
    private val syncScheduler: SyncScheduler,
    /** Application coordinator hook; the scheduler fallback keeps older containers source-compatible. */
    private val requestSync: (() -> Unit)? = null,
    /** Manual refresh bypasses transient cooldown once but remains coalesced with local work. */
    private val requestManualSyncCallback: (() -> Unit)? = null,
) {
    // Network work never holds the local-write lock. Account replacement shares the sync lock.
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var currentAccount = credentials.load()
    // A rotated credential stays in memory until its encrypted persistence succeeds. This barrier
    // is consulted while holding syncMutex so no resource request or second rotation can bypass it.
    private var pendingCredentialSave: Account? = null
    private var feedState = "NORMAL"
    private var feedSpace: String? = null

    // A bounded turn may yield between pages. Keep only the cursor and the bounded cache/fence
    // evidence needed to resume; process death safely restarts from a fresh scan snapshot.
    private data class ScanContinuation(
        val owner: String,
        val accountEpoch: Long,
        val scan: ScanSnapshot?,
        val pruneCandidates: Set<String>,
        val protectedAtStart: List<Memo>,
        val spaces: MutableList<Space> = mutableListOf(),
        val spaceTokens: MutableSet<String> = mutableSetOf(),
        val memos: LinkedHashMap<String, Memo> = linkedMapOf(),
        val listedForFence: LinkedHashMap<String, Memo> = linkedMapOf(),
        val remoteReminders: LinkedHashMap<String, ReminderRecord> = linkedMapOf(),
        val verifiedFences: LinkedHashMap<String, Memo?> = linkedMapOf(),
        var spaceToken: String? = null,
        var spacesComplete: Boolean = false,
        var scopeIndex: Int = 0,
        var stateIndex: Int = 0,
        var memoToken: String? = null,
        val memoTokens: MutableSet<String> = mutableSetOf(),
        var fenceIndex: Int = 0,
        var cacheIncomplete: Boolean = false,
    )

    private var accountEpoch = 0L
    private data class UploadBudget(
        var work: Int = 0,
        val attemptsByName: MutableMap<String, Int> = mutableMapOf(),
    )
    private var uploadBudget: UploadBudget? = null
    private var pullProgressThisTurn = false
    private var scanContinuation: ScanContinuation? = null
    private val mutableFeed = MutableStateFlow<List<Memo>>(emptyList())
    val feed: StateFlow<List<Memo>> = mutableFeed
    /** Account-scoped local view, including deletion tombstones for open-detail reconciliation. */
    private val mutableLocalMemos = MutableStateFlow<List<Memo>>(emptyList())
    val localMemos: StateFlow<List<Memo>> = mutableLocalMemos
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
            lastUploadAckAt = lastUploadAckAt(),
            lastReconciledAt = lastSyncedAt(),
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
    /** Non-blocking wake request. A scheduling failure cannot undo a committed local save. */
    fun scheduleSync() = runCatching { requestSync?.invoke() ?: syncScheduler.enqueueRepair() }
    fun requestManualSync() = runCatching {
        requestManualSyncCallback?.invoke() ?: syncScheduler.enqueueRepair()
    }
    private fun owner(account: Account) = account.baseUrl + "\n" + account.userName
    private fun lastSyncedAt(): Instant? = database.metadata("last_sync")?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
    private fun lastUploadAckAt(): Instant? = database.metadata("last_upload_ack")?.let {
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

    /** Compatibility wrapper for callers that only need the active account. */
    suspend fun refresh(): Account? = syncTurn().account

    /** One bounded network turn. WorkManager/coordinators can use [moreWork] without re-entry. */
    suspend fun syncTurn(): SyncTurnResult = networkLocked {
        val stored = currentAccount ?: return@networkLocked SyncTurnResult(null)
        if (authRequirementIsTerminal(stored)) {
            syncState.value = syncState.value.copy(
                signInRequired = true, failed = false, phase = SyncPhase.WAITING_FOR_AUTH,
            )
            throw AppException(AppError.Authentication)
        }
        val ackAtBeforeTurn = syncState.value.lastUploadAckAt
        val reconciledAtBeforeTurn = syncState.value.lastReconciledAt
        uploadBudget = UploadBudget()
        pullProgressThisTurn = false
        syncState.value = syncState.value.copy(
            syncing = true, failed = false, phase = SyncPhase.UPLOADING,
            pushError = null, pullError = null,
            uploadAckedThisTurn = false, reconciledThisTurn = false,
        )
        var refreshAttempted = false
        fun rotate(account: Account): Account {
            refreshAttempted = true
            return refreshSession(account)
        }
        try {
            retryPendingCredentialSave()
            val account = ensureValidSession(stored, ::rotate)
            var moreWork = false
            try {
                moreWork = synchronize(account)
            } catch (error: AppException) {
                if (error.error != AppError.Authentication || account.authMethod != AuthMethod.SESSION ||
                    refreshAttempted
                ) throw error
                // A rejected access token does not prove the session is lost. Rotate the refresh
                // token once and retry the complete bounded turn. The retry budget is deliberately one.
                moreWork = synchronize(rotate(account))
            }
            SyncTurnResult(
                account = currentAccount,
                moreWork = moreWork || scanContinuation != null || locked {
                    database.pending().any { it.status == MemoSyncStatus.PENDING }
                },
                pushProgress = syncState.value.lastUploadAckAt != ackAtBeforeTurn,
                pullProgress = pullProgressThisTurn,
                pullCompleted = syncState.value.lastReconciledAt != reconciledAtBeforeTurn,
            )
        } catch (error: AppException) {
            // Once a session has already rotated, a further resource 401 is an exhausted,
            // retryable sync failure—not evidence that the refresh credential was revoked.
            val reportedError = if (error.error == AppError.Authentication &&
                stored.authMethod == AuthMethod.SESSION && refreshAttempted
            ) AppError.ResourceAuthentication else error.error
            val terminal = terminalAuthenticationFailure(stored, reportedError, refreshAttempted)
            if (terminal) {
                locked {
                    database.setMetadata(AUTH_REQUIRED, "true")
                    database.setMetadata(AUTH_REQUIRED_REASON, when {
                        reportedError == AppError.SessionRefreshRejected -> REFRESH_REJECTED
                        stored.authMethod == AuthMethod.SESSION -> NO_REFRESH_CREDENTIAL
                        else -> ""
                    })
                }
            }
            syncState.value = syncState.value.copy(
                signInRequired = terminal,
                failed = !terminal,
                phase = if (terminal) SyncPhase.WAITING_FOR_AUTH else SyncPhase.IDLE,
                pushError = if (syncState.value.phase == SyncPhase.UPLOADING) reportedError else syncState.value.pushError,
                pullError = if (syncState.value.phase == SyncPhase.RECONCILING) reportedError else syncState.value.pullError,
            )
            if (reportedError == error.error) throw error else throw AppException(reportedError)
        } finally {
            uploadBudget = null
            syncState.value = syncState.value.copy(syncing = false,
                phase = if (syncState.value.signInRequired) SyncPhase.WAITING_FOR_AUTH else SyncPhase.IDLE)
        }
    }

    private suspend fun synchronize(startingAccount: Account): Boolean {
        var account = startingAccount
        val scanStartedAt = System.nanoTime()
        // Upload first. A profile/capability endpoint being unavailable must not strand plain
        // text outbox work; capability is discovered after this push phase.
        val initialFlush = flush(account)
        if (initialFlush.retryableFailure) {
            syncState.value = syncState.value.copy(pushError = AppError.Network, failed = true)
            throw AppException(AppError.Network)
        }
        // Do not spend a pull turn while eligible durable writes are still waiting behind the
        // per-turn upload budget. The next turn starts upload-first again.
        if (initialFlush.moreWork) return true

        syncState.value = syncState.value.copy(phase = SyncPhase.RECONCILING)
        val supported = try {
            api.supportsMemoReminderTime(account.baseUrl, account.token)
        } catch (error: AppException) {
            // A save may have committed while capability discovery was in flight.
            runCatching { flush(account) }
            syncState.value = syncState.value.copy(pullError = error.error, failed = true)
            throw error
        }
        account = account.withCapability(supported)
        currentAccount = account
        // This is both the capability snapshot and a checked persistence point for a rotated
        // refresh credential. Do not issue another resource request if it cannot be committed.
        persistCredential(account)
        mutableAccount.value = account.summary()
        if (!supported) locked { reminders.clearReminders() }
        clearAuthRequirement()

        val continuation = if (scanContinuation?.owner == owner(account) &&
            scanContinuation?.accountEpoch == accountEpoch
        ) {
            scanContinuation!!
        } else {
            val pruneCandidates = locked {
                database.localMemos().filter { it.syncStatus == MemoSyncStatus.SYNCED }
                    .map { it.name }.toSet()
            }
            val protectedAtStart = locked { database.pending().map { it.desired } }
            ScanContinuation(
                owner = owner(account), accountEpoch = accountEpoch,
                scan = locked { database.beginScan() }, pruneCandidates = pruneCandidates,
                protectedAtStart = protectedAtStart,
            ).also { scanContinuation = it }
        }
        val fenceNames = continuation.scan?.fences?.map { it.name }?.toSet().orEmpty()
        val protectedAtStartNames = continuation.protectedAtStart.map { it.name }.toSet()
        var pagesThisTurn = 0

        suspend fun uploadBoundary(): Boolean {
            val boundary = flush(account)
            if (boundary.retryableFailure) {
                syncState.value = syncState.value.copy(pushError = AppError.Network, failed = true)
                throw AppException(AppError.Network)
            }
            return boundary.moreWork
        }

        // The cursor is intentionally retained between turns. This is the important distinction
        // from restarting a 128-page prefix: a large inventory eventually reaches its tail.
        while (!continuation.spacesComplete) {
            if (pagesThisTurn++ >= MAX_SCAN_PAGES ||
                System.nanoTime() - scanStartedAt > MAX_SCAN_NANOS
            ) return true
            val page: com.vstokke.memos.domain.SpacePage? = try {
                api.listSpacesPage(account, continuation.spaceToken)
            } catch (error: AppException) {
                runCatching { flush(account) }
                syncState.value = syncState.value.copy(pullError = error.error, failed = true)
                throw error
            }
            if (page == null) {
                // Mockito/older API implementations may only provide the compatibility method.
                try {
                    continuation.spaces += api.listSpaces(account)
                } catch (error: AppException) {
                    runCatching { flush(account) }
                    syncState.value = syncState.value.copy(pullError = error.error, failed = true)
                    throw error
                }
                continuation.spaceToken = null
                continuation.spacesComplete = true
            } else {
                continuation.spaces += page.spaces
                continuation.spaceToken = page.nextPageToken
                page.nextPageToken?.let {
                    if (!continuation.spaceTokens.add(it)) throw AppException(AppError.InvalidResponse)
                }
                continuation.spacesComplete = page.nextPageToken == null
            }
            pullProgressThisTurn = true
            if (uploadBoundary()) return true
        }

        val scopes = listOf<String?>(null) + continuation.spaces.distinctBy { it.name }.map { it.name }
        val states = listOf("NORMAL", "ARCHIVED")
        while (continuation.scopeIndex < scopes.size) {
            val space = scopes[continuation.scopeIndex]
            val state = states[continuation.stateIndex]
            if (pagesThisTurn++ >= MAX_SCAN_PAGES ||
                System.nanoTime() - scanStartedAt > MAX_SCAN_NANOS
            ) return true
            val page = try {
                api.listPage(account, state, continuation.memoToken, space, pageSize = 1_000)
            } catch (error: AppException) {
                // Give work queued while the pull was in flight one final upload boundary,
                // but never apply a partial cache as a complete scan.
                runCatching { flush(account) }
                syncState.value = syncState.value.copy(pullError = error.error, failed = true)
                throw error
            }
            pullProgressThisTurn = true
            val protected = locked { database.pending().map { it.desired } }
            page.memos.filter { it.state == state &&
                (if (space == null) it.creator == account.userName else it.space == space)
            }.forEach { memo ->
                if (memo.name in fenceNames) continuation.listedForFence[memo.name] = memo
                if (memo.creator == account.userName && memo.state == "NORMAL") memo.reminderTime?.let {
                    continuation.remoteReminders[memo.name] = ReminderRecord(
                        memo.name, it, memo.snippet.ifBlank { memo.content },
                    )
                }
                if (memo.name !in protectedAtStartNames) {
                    continuation.memos[memo.name] = memo
                }
            }
            val retained = CacheBudget.retain(
                continuation.memos.values.toList(), 100L * 1024 * 1024, protected,
            )
            if (retained.size < continuation.memos.size) continuation.cacheIncomplete = true
            continuation.memos.clear(); retained.forEach { continuation.memos[it.name] = it }
            continuation.memoToken = page.nextPageToken
            page.nextPageToken?.let {
                if (!continuation.memoTokens.add(it)) throw AppException(AppError.InvalidResponse)
            }
            // Finalize the cursor before the boundary can throw. A failed upload must resume at
            // the next page/state/scope, never replay a completed page prefix.
            if (continuation.memoToken == null) {
                continuation.memoTokens.clear()
                continuation.stateIndex++
                if (continuation.stateIndex == states.size) {
                    continuation.stateIndex = 0
                    continuation.scopeIndex++
                }
            }
            if (uploadBoundary()) return true
        }

        // Fence mismatches/omissions are revalidated individually. The fence cursor is retained
        // too, so a large set of stale ACKs cannot repeat the same first eight GETs forever.
        val fences = continuation.scan?.fences.orEmpty()
        var targetedChecks = 0
        while (continuation.fenceIndex < fences.size) {
            if (System.nanoTime() - scanStartedAt > MAX_SCAN_NANOS) return true
            val fence = fences[continuation.fenceIndex]
            val listed = continuation.listedForFence[fence.name]
            val listCorroborates = listed != null && fence.snapshot?.sameSnapshot(listed) == true
            if (listCorroborates) {
                continuation.verifiedFences[fence.name] = listed
                continuation.fenceIndex++
                pullProgressThisTurn = true
                continue
            }
            if (listed == null && fence.deleted) {
                // The feed cursor covered the complete inventory, so absence corroborates a delete
                // even when that memo was evicted from the local cache.
                continuation.verifiedFences[fence.name] = null
                continuation.fenceIndex++
                pullProgressThisTurn = true
                continue
            }
            if (targetedChecks++ >= MAX_TARGETED_REVALIDATIONS) return true
            try {
                continuation.verifiedFences[fence.name] = getRemote(account, fence.name)
            } catch (error: AppException) {
                runCatching { flush(account) }
                syncState.value = syncState.value.copy(pullError = error.error, failed = true)
                throw error
            }
            continuation.fenceIndex++
            pullProgressThisTurn = true
            // A targeted request is a boundary too, so a new local save is not hidden until the
            // next whole sync.
            if (uploadBoundary()) return true
        }

        val cacheIncomplete = continuation.cacheIncomplete
        val scan = continuation.scan
        // Keep the existing applyScan call shape for repository fakes while carrying the
        // distinction that quota omission is not an incomplete remote inventory.
        scan?.inventoryComplete = true
        val spaces = continuation.spaces.distinctBy { it.name }
        val all = continuation.memos.values.toList()
        val verified = continuation.verifiedFences.toMap()
        locked {
            if (scan == null) {
                // Preserve the narrow compatibility seam used by older repository fakes.
                database.applyScan(all, spaces, 100L * 1024 * 1024, cacheIncomplete,
                    continuation.pruneCandidates)
            } else {
                database.applyScan(all, spaces, incomplete = cacheIncomplete,
                    pruneCandidates = continuation.pruneCandidates, scan = scan,
                    verifiedFences = verified)
            }
            mutableSpaces.value = database.spaces()
            // Reminder inventory follows the same fence-aware accepted set as feed inventory.
            // Raw stale rows must not resurrect an acknowledged deletion or stale due time.
            val acceptedReminders = continuation.remoteReminders.toMutableMap()
            verified.forEach { (name, memo) ->
                acceptedReminders.remove(name)
                memo?.takeIf { it.creator == account.userName && it.state == "NORMAL" }
                    ?.reminderTime?.let {
                        acceptedReminders[name] = ReminderRecord(
                            name, it, memo.snippet.ifBlank { memo.content },
                        )
                    }
            }
            database.fences().forEach { fence ->
                acceptedReminders.remove(fence.name)
                fence.snapshot?.takeIf { it.creator == account.userName && it.state == "NORMAL" }
                    ?.reminderTime?.let {
                        acceptedReminders[fence.name] = ReminderRecord(
                            fence.name, it, fence.snapshot.snippet.ifBlank { fence.snapshot.content },
                        )
                    }
            }
            reconcileLocalReminders(account, acceptedReminders.values.toList())
            publishLocal()
        }
        scanContinuation = null
        syncState.value = syncState.value.copy(
            pullError = null, lastReconciledAt = Instant.now(), failed = false,
            reconciledThisTurn = true,
        )
        reminders.syncSucceeded()
        // Conflicts and permanent failures remain visible but are not eligible work. Do not let
        // them make a coordinator spin after the inventory has completed.
        return locked { database.pending().any { it.status == MemoSyncStatus.PENDING } }
    }

    private data class FlushResult(val retryableFailure: Boolean, val moreWork: Boolean, val acknowledged: Int = 0)

    /** Flushes a bounded, fresh view of the outbox. New names are picked up at each boundary. */
    private suspend fun flush(account: Account): FlushResult {
        var retryableFailure = false
        var moreWork = false
        var acknowledged = 0
        val budget = uploadBudget ?: UploadBudget().also { uploadBudget = it }
        while (budget.work < MAX_UPLOAD_WORK_PER_TURN) {
            val operation = locked {
                database.pending().firstOrNull {
                    it.status == MemoSyncStatus.PENDING &&
                        (budget.attemptsByName[it.desired.name] ?: 0) < MAX_UPLOAD_STEPS_PER_MEMO
                }
            } ?: break
            // Empty pending polls do not consume this turn's upload allowance.
            budget.work++
            budget.attemptsByName[operation.desired.name] =
                (budget.attemptsByName[operation.desired.name] ?: 0) + 1
            val before = operation.revision
            try {
                upload(account, operation)
                acknowledged += locked {
                    val current = database.pending().firstOrNull { it.desired.name == operation.desired.name }
                    if (current == null || current.revision != before) 1 else 0
                }
            } catch (error: AppException) {
                if (error.error == AppError.Authentication) throw error
                // A timeout leaves the frozen dispatched intent available for GET-based recovery.
                val retryable = error.error == AppError.Network ||
                    error.error is AppError.Server && (error.error.status == 429 || error.error.status >= 500)
                if (retryable) {
                    retryableFailure = true
                    syncState.value = syncState.value.copy(
                        failed = true, phase = SyncPhase.UPLOADING, pushError = error.error,
                    )
                } else {
                    locked { database.markIssue(operation.desired.name, MemoSyncStatus.FAILED, null); publishLocal() }
                }
                break
            }
        }
        moreWork = locked { database.pending().any { it.status == MemoSyncStatus.PENDING } }
        return FlushResult(retryableFailure, moreWork, acknowledged)
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
            syncState.value = syncState.value.copy(
                lastUploadAckAt = Instant.now(), pushError = null, uploadAckedThisTurn = true,
            )
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
        syncState.value = syncState.value.copy(
            lastUploadAckAt = Instant.now(), pushError = null, uploadAckedThisTurn = true,
        )
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

    /** Queues only content/snippet changes for an interactive Markdown task marker. */
    suspend fun editContent(expected: AccountSummary, base: Memo, content: String): Memo = locked {
        val account = requireLocalAccount(expected)
        val local = requireEditable(account, base)
        val desired = local.copy(content = content, snippet = content)
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
        val persisted = credentials.load()
        val currentMatchesExpected = current.baseUrl == expected.baseUrl && current.userName == expected.userName
        val ownerMatches = database.metadata("owner") == owner(current)
        val persistedMatches = persisted?.baseUrl == current.baseUrl && persisted.userName == current.userName
        // A failed activation rollback may leave only the credential store's temporary view on
        // another account. Permit local work solely when the active/current/expected/DB identities
        // match and that exact active identity is explicitly queued for persistence recovery.
        val pendingMatches = pendingCredentialSave?.baseUrl == current.baseUrl &&
            pendingCredentialSave?.userName == current.userName &&
            pendingCredentialSave?.authMethod == current.authMethod
        if (!currentMatchesExpected || !ownerMatches || (!persistedMatches && !pendingMatches)) {
            throw AppException(AppError.Authentication)
        }
        return current // Access-token expiry cannot prevent a local save.
    }
    private fun localWriteFinished(account: Account) {
        reconcileLocalReminders(account)
        publishLocal()
        // The SQLite transaction has already committed. WorkManager/coordinator failures are
        // intentionally swallowed; durable outbox state is the recovery source of truth.
        runCatching { requestSync?.invoke() ?: syncScheduler.enqueueRepair() }
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
        val allLocalMemos = if (allowed) database.localMemos(includeDeleted = true) else emptyList()
        mutableLocalMemos.value = allLocalMemos
        mutableFeed.value = memos.filter { it.state == feedState &&
            (if (feedSpace == null) it.creator == account?.userName else it.space == feedSpace) }
        syncIssues.value = if (allowed) database.pending() else emptyList()
        syncState.value = syncState.value.copy(
            pending = syncIssues.value.count { it.status == MemoSyncStatus.PENDING },
            conflicts = syncIssues.value.count { it.status == MemoSyncStatus.CONFLICT },
            failedCount = syncIssues.value.count { it.status == MemoSyncStatus.FAILED },
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
        val replacingOwner = previousOwner != null && previousOwner != owner(account)
        // The pending-work guard must precede credential persistence: a rejected account cannot
        // replace the active account merely because its credential happened to save.
        if (replacingOwner && database.pending().isNotEmpty()) throw AppException(AppError.PendingAccount)
        val previousAccount = currentAccount
        try {
            // Keep database ownership, reminders, currentAccount and public state unchanged until
            // the new credential is checked. A failed SharedPreferences commit may nevertheless
            // update its in-memory view, so restore the still-active account for this activation
            // failure (never the older credential after a same-owner refresh rotation).
            credentials.save(account)
        } catch (error: Exception) {
            previousAccount?.let { previous ->
                runCatching { credentials.save(previous) }
                    .onFailure { pendingCredentialSave = previous }
            }
            throw error
        }
        pendingCredentialSave = null
        // A sign-in/activation is a new account epoch even for the same owner. Any in-memory
        // scan continuation was tied to the previous authenticated view of the account.
        accountEpoch++
        scanContinuation = null
        if (replacingOwner) {
            reminders.clearReminders(); reminders.clearDiagnostics(); database.clearAll()
            credentials.saveSelectedSpace(null); feedSpace = null; feedState = "NORMAL"
        }
        database.setMetadata("owner", owner(account))
        database.setMetadata(AUTH_REQUIRED, "false")
        database.setMetadata(AUTH_REQUIRED_REASON, "")
        currentAccount = account
        mutableAccount.value = account.summary()
        mutableSpaces.value = if (database.metadata("spaces_known") == "true") database.spaces() else null
        syncState.value = syncState.value.copy(signInRequired = false)
        publishLocal()
        runCatching { syncScheduler.ensurePeriodicSync() }
        runCatching { requestSync?.invoke() ?: syncScheduler.enqueueRepair() }
        account
    }
    private fun ensureValidSession(account: Account, rotate: (Account) -> Account): Account {
        if (account.authMethod != AuthMethod.SESSION) return account
        if (account.token.isNotBlank() && account.accessTokenExpiresAt?.isAfter(Instant.now().plusSeconds(30)) == true) return account
        if (account.refreshToken.isNullOrBlank()) throw AppException(AppError.Authentication)
        return rotate(account)
    }

    private fun refreshSession(account: Account): Account {
        val refreshToken = account.refreshToken ?: throw AppException(AppError.Authentication)
        val tokens = try {
            api.refreshSession(account.baseUrl, refreshToken)
        } catch (error: AppException) {
            // Keep compatibility with API implementations/mocks that still report refresh 401 as
            // generic Authentication; this boundary is known to be the refresh endpoint.
            if (error.error == AppError.Authentication) throw AppException(AppError.SessionRefreshRejected)
            throw error
        }
        val refreshed = Account(account.baseUrl, tokens.accessToken, account.userName, account.displayName,
            account.supportsMemoReminderTime, AuthMethod.SESSION, tokens.refreshToken, tokens.accessTokenExpiresAt)
        // Publish the newest token before saving. A failed commit must not replace it in memory or
        // fall back to the older credential on a later request; refresh() will retry this save
        // before doing anything else on the next attempt.
        currentAccount = refreshed
        persistCredential(refreshed)
        return refreshed
    }

    private fun persistCredential(account: Account) {
        try {
            credentials.save(account)
            pendingCredentialSave = null
        } catch (error: AppException) {
            pendingCredentialSave = account
            throw error
        }
    }

    private fun retryPendingCredentialSave() {
        val pending = pendingCredentialSave ?: return
        try {
            credentials.save(pending)
            pendingCredentialSave = null
        } catch (error: AppException) {
            // Keep the newest account as the barrier. In particular, do not rotate or make a
            // resource request merely because its access token is still valid or has expired.
            throw error
        }
    }

    private fun authRequirementIsTerminal(account: Account): Boolean {
        if (database.metadata(AUTH_REQUIRED) != "true") return false
        if (account.authMethod != AuthMethod.SESSION) return true
        return database.metadata(AUTH_REQUIRED_REASON) in setOf(REFRESH_REJECTED, NO_REFRESH_CREDENTIAL)
    }

    private fun terminalAuthenticationFailure(
        account: Account,
        error: AppError,
        refreshAttempted: Boolean,
    ): Boolean = when {
        error == AppError.SessionRefreshRejected -> true
        account.authMethod != AuthMethod.SESSION && error == AppError.Authentication -> true
        account.authMethod == AuthMethod.SESSION && error == AppError.Authentication &&
            !refreshAttempted && account.refreshToken.isNullOrBlank() -> true
        else -> false
    }

    private suspend fun clearAuthRequirement() {
        locked {
            database.setMetadata(AUTH_REQUIRED, "false")
            database.setMetadata(AUTH_REQUIRED_REASON, "")
        }
        syncState.value = syncState.value.copy(signInRequired = false)
    }
    private fun Account.withCapability(supported: Boolean) = Account(baseUrl, token, userName, displayName,
        supported, authMethod, refreshToken, accessTokenExpiresAt)

    private companion object {
        const val MAX_UPLOAD_STEPS_PER_MEMO = 8
        const val MAX_UPLOAD_WORK_PER_TURN = 32
        const val MAX_SCAN_PAGES = 128
        const val MAX_SCAN_NANOS = 25_000_000_000L
        const val MAX_TARGETED_REVALIDATIONS = 8
        const val AUTH_REQUIRED = "auth_required"
        const val AUTH_REQUIRED_REASON = "auth_required_reason"
        const val REFRESH_REJECTED = "refresh_rejected"
        const val NO_REFRESH_CREDENTIAL = "no_refresh_credential"
    }

    suspend fun logout() = networkLocked {
        val account = currentAccount
        if (account?.authMethod == AuthMethod.SESSION) runCatching {
            api.signOut(ensureValidSession(account, ::refreshSession))
        }
        locked {
            syncScheduler.cancel(); reminders.clearReminders(); reminders.clearDiagnostics()
            pendingCredentialSave = null
            accountEpoch++
            scanContinuation = null
            currentAccount = null; credentials.clear(); database.clearAll()
            mutableAccount.value = null; mutableSpaces.value = emptyList()
            feedState = "NORMAL"; feedSpace = null; syncState.value = SyncState(); publishLocal()
        }
    }
}
