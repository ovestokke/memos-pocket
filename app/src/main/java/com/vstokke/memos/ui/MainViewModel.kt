package com.vstokke.memos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.domain.*

// Draft lives in the ViewModel, not a feed item: refresh and rotation cannot overwrite it.
data class EditorDraft(val base: Memo?, val content: String, val visibility: String, val reminder: String) {
    val changed: Boolean get() = content != (base?.content ?: "") || visibility != (base?.visibility ?: "PRIVATE") ||
        reminder != (base?.reminderTime?.toString() ?: "")
}

data class MainUiState(
    val account: AccountSummary? = null,
    val feed: List<Memo> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val conflict: Boolean = false,
    val archive: Boolean = false,
    val hasMore: Boolean = false,
    val detail: Memo? = null,
    val draft: EditorDraft? = null,
)

class MainViewModel(private val repository: MemoRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState(account = repository.account()?.summary()))
    val state: StateFlow<MainUiState> = mutableState

    init {
        viewModelScope.launch { repository.feed.collect { feed -> mutableState.update { it.copy(feed = feed) } } }
        viewModelScope.launch { repository.accountSummary.collect { account -> mutableState.update { it.copy(account = account) } } }
        viewModelScope.launch { repository.hasMore.collect { more -> mutableState.update { it.copy(hasMore = more) } } }
        if (mutableState.value.account != null) refresh()
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, notice = null, conflict = false) }
        viewModelScope.launch {
            try { block() }
            catch (error: AppException) { mutableState.update { it.copy(error = error.error.userMessage(), conflict = error.error == AppError.Conflict) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.update { it.copy(error = "The operation could not finish. Your draft has been kept.") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun connect(instanceInput: String, tokenInput: String) {
        val base = InstanceUrl.normalize(instanceInput)
        if (base == null || tokenInput.isBlank()) {
            mutableState.update { it.copy(error = "Enter an HTTPS server address and personal access token.") }; return
        }
        runOperation { repository.configure(base, tokenInput.trim()); repository.refresh() }
    }

    fun refresh() {
        val expected = state.value.account ?: return
        runOperation {
            repository.refresh()
            val detail = state.value.detail
            if (detail != null && state.value.draft == null) {
                val updated = repository.get(expected, detail.name)
                mutableState.update { it.copy(detail = updated) }
            }
        }
    }
    fun page(archive: Boolean, more: Boolean = false) {
        val account = state.value.account ?: return
        runOperation {
            repository.page(account, archive, more)
            mutableState.update { it.copy(archive = archive, detail = null) }
        }
    }
    fun open(name: String) {
        val account = state.value.account ?: return
        runOperation { val memo = repository.get(account, name); mutableState.update { it.copy(detail = memo) } }
    }
    fun closeDetail() { mutableState.update { it.copy(detail = null) } }
    fun beginEdit(memo: Memo? = null) {
        if (state.value.busy || state.value.draft != null) return
        mutableState.update { it.copy(draft = EditorDraft(memo, memo?.content ?: "", memo?.visibility ?: "PRIVATE", memo?.reminderTime?.toString() ?: ""), error = null) }
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
        runOperation {
            val memo: Memo
            var preserved = true
            if (draft.base == null) {
                val result = repository.create(account, NewMemo(draft.content, reminder, draft.visibility))
                memo = result.memo; preserved = result.reminderPreserved
            } else {
                memo = repository.edit(account, draft.base, MemoEdit(draft.content, draft.visibility, reminder))
                preserved = memo.reminderTime == reminder
            }
            mutableState.update { it.copy(draft = null, detail = if (draft.base == null) null else memo,
                notice = if (preserved) null else "Saved, but the server did not preserve the reminder. Check Settings; delivery is not confirmed.") }
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
    fun logout() { runOperation { repository.logout(); mutableState.value = MainUiState(busy = true) } }

    class Factory(private val repository: MemoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
    }
}
