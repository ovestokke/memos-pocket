package com.vstokke.memos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vstokke.memos.data.PendingMemo
import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.domain.*

// Draft lives in the ViewModel, not a feed item: refresh and rotation cannot overwrite it.
data class EditorDraft(
    val base: Memo?,
    val content: String,
    val visibility: String,
    val reminder: String,
    val space: String? = base?.space,
) {
    val changed: Boolean get() = content != (base?.content ?: "") || visibility != (base?.visibility ?: "PRIVATE") ||
        reminder != (base?.reminderTime?.toString() ?: "") || space != base?.space
}

data class MainUiState(
    val account: AccountSummary? = null,
    val feed: List<Memo> = emptyList(),
    val busy: Boolean = false,
    val showProgress: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val conflict: Boolean = false,
    val archive: Boolean = false,
    val hasMore: Boolean = false,
    val spaces: List<Space> = emptyList(),
    val selectedSpace: String? = null,
    val detail: Memo? = null,
    val draft: EditorDraft? = null,
    val signInBaseUrl: String? = null,
    val signInOptions: SignInOptions? = null,
    val oauthLaunchUrl: String? = null,
    val sync: SyncState = SyncState(),
    val syncIssues: List<PendingMemo> = emptyList(),
    val reauthenticating: Boolean = false,
)

private data class FeedSelection(val archive: Boolean, val space: String?)

private fun MainUiState.selection() = FeedSelection(archive, selectedSpace)

class MainViewModel(private val repository: MemoRepository) : ViewModel() {
    private val defaultSelection = FeedSelection(archive = false, space = null)
    private val initialSelection = FeedSelection(archive = false, space = repository.selectedSpace())
    private val pageFeeds = mutableMapOf(defaultSelection to repository.feed.value)
    private val pageHasMore = mutableMapOf(defaultSelection to repository.hasMore.value)
    private var repositorySelection = defaultSelection
    private var feedEmissionTarget: FeedSelection? = null
    private var pendingPage: FeedSelection? = null
    private val mutableState = MutableStateFlow(
        MainUiState(
            account = repository.account()?.summary(),
            feed = pageFeeds[initialSelection].orEmpty(),
            selectedSpace = initialSelection.space,
        ),
    )
    val state: StateFlow<MainUiState> = mutableState

    init {
        viewModelScope.launch { repository.syncState.collect { sync -> mutableState.update { it.copy(sync = sync) } } }
        viewModelScope.launch { repository.syncIssues.collect { issues -> mutableState.update { it.copy(syncIssues = issues) } } }
        viewModelScope.launch {
            repository.feed.collect { feed ->
                val target = feedEmissionTarget ?: repositorySelection
                pageFeeds[target] = feed
                if (mutableState.value.selection() == target) {
                    mutableState.update { it.copy(feed = feed) }
                }
            }
        }
        viewModelScope.launch { repository.accountSummary.collect { account -> mutableState.update { it.copy(account = account) } } }
        viewModelScope.launch {
            repository.hasMore.collect { more ->
                val target = feedEmissionTarget ?: repositorySelection
                pageHasMore[target] = more
                if (mutableState.value.selection() == target) {
                    mutableState.update { it.copy(hasMore = more) }
                }
            }
        }
        viewModelScope.launch {
            repository.spaces.collect { spaces ->
                if (spaces == null) return@collect
                mutableState.update { it.copy(spaces = spaces) }
                val selected = mutableState.value.selectedSpace
                if (selected != null && spaces.none { it.name == selected }) {
                    repository.saveSelectedSpace(null)
                    val target = FeedSelection(mutableState.value.archive, null)
                    selectPage(target)
                    if (mutableState.value.busy) pendingPage = target else requestPage(target)
                }
            }
        }
        if (mutableState.value.account != null) {
            requestPage(initialSelection)
            repository.scheduleSync()
        }
    }

    private fun runOperation(
        showProgress: Boolean = true,
        saving: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (mutableState.value.busy) return
        mutableState.update {
            it.copy(
                busy = true,
                showProgress = showProgress,
                saving = saving,
                error = null,
                notice = null,
                conflict = false,
            )
        }
        viewModelScope.launch {
            try { block() }
            catch (error: AppException) {
                mutableState.update { it.copy(error = error.error.userMessage(), conflict = error.error == AppError.Conflict) }
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.update {
                    it.copy(error = if (it.draft == null) "The operation could not finish." else "The operation could not finish. Your draft has been kept.")
                }
            }
            finally {
                mutableState.update { it.copy(busy = false, showProgress = false, saving = false) }
                val target = pendingPage
                pendingPage = null
                if (target != null && target != repositorySelection) requestPage(target)
            }
        }
    }

    fun loadSignInOptions(instanceInput: String) {
        val base = InstanceUrl.normalize(instanceInput)
        if (base == null) {
            mutableState.update { it.copy(error = "Enter a valid HTTPS Memos server address.") }
            return
        }
        runOperation {
            val options = repository.signInOptions(base)
            mutableState.update { it.copy(signInBaseUrl = base, signInOptions = options) }
        }
    }

    fun signInWithPassword(username: String, password: String) {
        val base = state.value.signInBaseUrl ?: return
        if (username.isBlank() || password.isBlank()) return
        runOperation {
            repository.signInWithPassword(base, username.trim(), password)
            finishSignIn()
        }
    }

    fun startSso(providerName: String) {
        val current = state.value
        val base = current.signInBaseUrl ?: return
        val provider = current.signInOptions?.providers?.firstOrNull { it.name == providerName } ?: return
        runOperation {
            val url = repository.beginSso(base, provider)
            mutableState.update { it.copy(oauthLaunchUrl = url) }
        }
    }

    fun consumeOAuthLaunch(failed: Boolean = false) {
        mutableState.update {
            it.copy(
                oauthLaunchUrl = null,
                error = if (failed) "No browser is available to complete sign-in." else it.error,
            )
        }
    }

    fun completeSso(callbackUrl: String) {
        runOperation {
            repository.completeSso(callbackUrl)
            finishSignIn()
        }
    }

    private suspend fun finishSignIn() {
        mutableState.update { it.copy(reauthenticating = false) }
        val account = repository.account()?.summary() ?: return
        repository.page(account, state.value.archive, space = state.value.selectedSpace)
        repository.scheduleSync()
    }

    fun refresh() = repository.scheduleSync()

    fun requestSignIn() {
        mutableState.update { it.copy(reauthenticating = true, signInBaseUrl = null, signInOptions = null) }
    }
    fun cancelSignIn() { mutableState.update { it.copy(reauthenticating = false) } }
    fun resolveConflict(name: String, preserveAsNew: Boolean) {
        val account = state.value.account ?: return
        runOperation {
            repository.resolveConflict(account, name, preserveAsNew)
            mutableState.update { it.copy(detail = null) }
        }
    }

    fun retryFailed(name: String) {
        val account = state.value.account ?: return
        runOperation(showProgress = false) { repository.retryFailed(account, name) }
    }

    private fun selectPage(selection: FeedSelection) {
        mutableState.update {
            it.copy(
                archive = selection.archive,
                selectedSpace = selection.space,
                feed = pageFeeds[selection].orEmpty(),
                hasMore = pageHasMore[selection] ?: false,
                detail = null,
            )
        }
    }

    fun selectSpace(space: String?) {
        if (space != null && state.value.spaces.none { it.name == space }) return
        val current = state.value
        if (current.selectedSpace == space) return
        repository.saveSelectedSpace(space)
        val draft = current.draft
        val movedDraft = if (draft?.base == null && draft != null) {
            draft.copy(
                space = space,
                visibility = when {
                    space != null -> "SPACE"
                    draft.visibility == "SPACE" -> "PRIVATE"
                    else -> draft.visibility
                },
            )
        } else draft
        val selection = FeedSelection(current.archive, space)
        selectPage(selection)
        mutableState.update { it.copy(draft = movedDraft) }
        requestPage(selection)
    }

    fun page(archive: Boolean, more: Boolean = false) {
        val selection = FeedSelection(archive, state.value.selectedSpace)
        if (!more && state.value.selection() == selection) return
        selectPage(selection)
        requestPage(selection, more)
    }

    private fun requestPage(selection: FeedSelection, more: Boolean = false) {
        val account = state.value.account ?: return
        if (state.value.busy) {
            if (!more) pendingPage = selection
            return
        }
        runOperation(showProgress = false) {
            feedEmissionTarget = selection
            try {
                repository.page(account, selection.archive, more, selection.space)
                repositorySelection = selection
            } finally {
                feedEmissionTarget = null
            }
        }
    }
    fun open(name: String) {
        val account = state.value.account ?: return
        runOperation { val memo = repository.get(account, name); mutableState.update { it.copy(detail = memo) } }
    }
    fun closeDetail() { mutableState.update { it.copy(detail = null) } }
    fun beginEdit(memo: Memo? = null) {
        if (state.value.busy || state.value.draft != null) return
        val space = memo?.space ?: state.value.selectedSpace
        val visibility = memo?.visibility ?: if (space == null) "PRIVATE" else "SPACE"
        mutableState.update {
            it.copy(
                draft = EditorDraft(memo, memo?.content ?: "", visibility, memo?.reminderTime?.toString() ?: "", space),
                error = null,
            )
        }
    }
    fun updateDraft(draft: EditorDraft) { if (!state.value.busy) mutableState.update { it.copy(draft = draft) } }
    fun discardDraft() { if (!state.value.busy) mutableState.update { it.copy(draft = null) } }
    fun save() {
        val account = state.value.account ?: return
        val draft = state.value.draft ?: return
        if (draft.content.isBlank()) return
        val reminder = if (draft.reminder.isBlank()) null else ReminderTime.parseServer(draft.reminder)
        if (draft.reminder.isNotBlank() && reminder == null) {
            mutableState.update { it.copy(error = "Use an ISO timestamp with timezone, for example 2026-05-01T12:00:00Z, or clear the field.") }; return
        }
        runOperation(saving = true) {
            val memo: Memo
            var preserved = true
            if (draft.base == null) {
                val result = repository.create(account, NewMemo(draft.content, reminder, draft.visibility, draft.space))
                memo = result.memo; preserved = result.reminderPreserved
            } else {
                memo = repository.edit(account, draft.base, MemoEdit(draft.content, draft.visibility, reminder))
                preserved = memo.reminderTime == reminder
            }
            mutableState.update { it.copy(draft = null, detail = if (draft.base == null) null else memo,
                notice = when {
                    !preserved -> "Saved, but the server did not preserve the reminder. Check Settings; delivery is not confirmed."
                    memo.syncStatus == MemoSyncStatus.FAILED -> "Saved locally. Sync is stopped for this memo; review Settings."
                    else -> "Saved locally. Waiting to sync."
                }) }
        }
    }
    fun action(memo: Memo, action: String) {
        val account = state.value.account ?: return
        runOperation {
            val updated = repository.action(account, memo, action)
            mutableState.update { it.copy(detail = if (it.detail?.name == memo.name) updated else it.detail) }
        }
    }
    fun loadServerDraft() {
        val account = state.value.account ?: return
        val base = state.value.draft?.base ?: return
        runOperation {
            val memo = repository.get(account, base.name)
            mutableState.update { it.copy(draft = EditorDraft(memo, memo.content, memo.visibility, memo.reminderTime?.toString() ?: "")) }
        }
    }
    fun dismissError() { mutableState.update { it.copy(error = null, notice = null) } }
    fun logout() {
        runOperation {
            repository.logout()
            pageFeeds.clear()
            pageFeeds[defaultSelection] = emptyList()
            pageHasMore.clear()
            pageHasMore[defaultSelection] = false
            repositorySelection = defaultSelection
            feedEmissionTarget = null
            pendingPage = null
            mutableState.value = MainUiState(busy = true)
        }
    }

    class Factory(private val repository: MemoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
    }
}
