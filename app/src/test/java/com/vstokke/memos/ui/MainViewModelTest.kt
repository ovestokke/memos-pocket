package com.vstokke.memos.ui

import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mock(MemoRepository::class.java)
    private val account = Account("https://example.com", "secret", "users/alice", "Alice", true)
    private val feed = MutableStateFlow<List<Memo>>(emptyList())
    private val summary = MutableStateFlow<AccountSummary?>(account.summary())
    private val memo = Memo("memos/one", "Server", "Server", "PRIVATE", Instant.EPOCH, null, null, creator = "users/alice")
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        `when`(repository.account()).thenReturn(account)
        `when`(repository.feed).thenReturn(feed)
        `when`(repository.accountSummary).thenReturn(summary)
        `when`(repository.hasMore).thenReturn(MutableStateFlow(false))
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `inline creation clears draft and stays on feed without saved notice`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        `when`(repository.create(account.summary(), NewMemo("New thought", null, "PRIVATE")))
            .thenReturn(CreatedMemoResult(memo, true))
        val model = MainViewModel(repository)
        model.updateDraft(EditorDraft(null, "New thought", "PRIVATE", ""))
        model.save(); advanceUntilIdle()
        assertNull(model.state.value.draft)
        assertNull(model.state.value.detail)
        assertNull(model.state.value.notice)
    }

    @Test fun `failed save keeps exact draft and base for retry`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val edit = MemoEdit("Unsaved\n", "PRIVATE", null)
        `when`(repository.edit(account.summary(), memo, edit)).thenAnswer { throw AppException(AppError.Network) }
        val model = MainViewModel(repository)
        model.beginEdit(memo)
        val draft = model.state.value.draft!!.copy(content = "Unsaved\n")
        model.updateDraft(draft); model.save(); advanceUntilIdle()
        assertEquals(draft, model.state.value.draft)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }
    @Test fun `background feed and capability updates do not overwrite editor`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val model = MainViewModel(repository)
        model.beginEdit(memo)
        val draft = model.state.value.draft!!.copy(content = "Working draft")
        model.updateDraft(draft)
        feed.value = listOf(memo.copy(content = "Web change"))
        summary.value = account.summary().copy(supportsMemoReminderTime = false)
        advanceUntilIdle()
        assertEquals(draft, model.state.value.draft)
        assertFalse(model.state.value.account!!.supportsMemoReminderTime)
    }
    @Test fun `invalid reminder is not silently cleared or saved`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val model = MainViewModel(repository)
        model.beginEdit(memo)
        model.updateDraft(model.state.value.draft!!.copy(reminder = "tomorrow"))
        model.save(); advanceUntilIdle()
        assertEquals("tomorrow", model.state.value.draft!!.reminder)
        assertTrue(model.state.value.error!!.contains("timezone"))
    }
    @Test fun `conflict keeps draft until explicit server reload`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val edit = MemoEdit("Draft", "PRIVATE", null)
        `when`(repository.edit(account.summary(), memo, edit)).thenAnswer { throw AppException(AppError.Conflict) }
        `when`(repository.get(account.summary(), memo.name)).thenReturn(memo.copy(content = "Web version"))
        val model = MainViewModel(repository)
        model.beginEdit(memo)
        model.updateDraft(model.state.value.draft!!.copy(content = "Draft"))
        model.save(); advanceUntilIdle()
        assertTrue(model.state.value.conflict)
        assertEquals("Draft", model.state.value.draft!!.content)
        model.loadServerDraft(); advanceUntilIdle()
        assertEquals("Web version", model.state.value.draft!!.content)
        assertFalse(model.state.value.conflict)
    }
}
