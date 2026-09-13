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
    private val spaces = MutableStateFlow<List<Space>?>(emptyList())
    private val memo = Memo("memos/one", "Server", "Server", "PRIVATE", Instant.EPOCH, null, null, creator = "users/alice")
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        `when`(repository.account()).thenReturn(account)
        `when`(repository.feed).thenReturn(feed)
        `when`(repository.accountSummary).thenReturn(summary)
        `when`(repository.hasMore).thenReturn(MutableStateFlow(false))
        `when`(repository.spaces).thenReturn(spaces)
        `when`(repository.syncState).thenReturn(MutableStateFlow(SyncState()))
        `when`(repository.syncIssues).thenReturn(MutableStateFlow(emptyList()))
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `inline creation clears draft and reports durable local save`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        `when`(repository.create(account.summary(), NewMemo("New thought", null, "PRIVATE")))
            .thenReturn(CreatedMemoResult(memo, true))
        val model = MainViewModel(repository)
        model.updateDraft(EditorDraft(null, "New thought", "PRIVATE", ""))
        model.save(); advanceUntilIdle()
        assertNull(model.state.value.draft)
        assertNull(model.state.value.detail)
        assertEquals("Saved locally. Waiting to sync.", model.state.value.notice)
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
    @Test fun `page selection restores cached feeds immediately without navigation progress`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val normal = memo.copy(name = "memos/normal", content = "Normal")
        val archived = memo.copy(name = "memos/archived", content = "Archived", state = "ARCHIVED")
        lateinit var model: MainViewModel
        var showedNavigationProgress = true
        doAnswer {
            showedNavigationProgress = model.state.value.showProgress
            feed.value = listOf(archived)
            null
        }.`when`(repository).page(account.summary(), true, false)

        model = MainViewModel(repository)
        feed.value = listOf(normal)
        advanceUntilIdle()

        model.page(true)
        advanceUntilIdle()
        assertEquals(listOf(archived), model.state.value.feed)
        assertFalse(showedNavigationProgress)

        model.page(false)
        assertFalse(model.state.value.archive)
        assertEquals(listOf(normal), model.state.value.feed)
    }

    @Test fun `space selection is remembered and new drafts are created in that space`() = runTest(dispatcher) {
        `when`(repository.refresh()).thenReturn(account)
        val team = Space("spaces/team", "Team", "Shared notes")
        val created = memo.copy(visibility = "SPACE", space = team.name)
        `when`(repository.create(account.summary(), NewMemo("Together", null, "SPACE", team.name)))
            .thenReturn(CreatedMemoResult(created, true))
        val model = MainViewModel(repository)
        spaces.value = listOf(team)
        advanceUntilIdle()

        model.updateDraft(EditorDraft(null, "Together", "PRIVATE", ""))
        model.selectSpace(team.name)
        advanceUntilIdle()

        assertEquals(team.name, model.state.value.selectedSpace)
        assertEquals(team.name, model.state.value.draft!!.space)
        assertEquals("SPACE", model.state.value.draft!!.visibility)
        verify(repository).saveSelectedSpace(team.name)
        verify(repository).page(account.summary(), false, false, team.name)

        model.save()
        advanceUntilIdle()
        verify(repository).create(account.summary(), NewMemo("Together", null, "SPACE", team.name))
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
