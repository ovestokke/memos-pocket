package com.vstokke.memos.data

import com.vstokke.memos.domain.*
import com.vstokke.memos.reminders.ReminderCoordinator
import com.vstokke.memos.work.SyncScheduler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemoRepositoryTest {
    private val credentials = mock(CredentialStore::class.java)
    private val database = mock(AppDatabase::class.java)
    private val api = mock(MemosApi::class.java)
    private val reminders = mock(ReminderCoordinator::class.java)
    private val scheduler = mock(SyncScheduler::class.java)
    private var stored: Account? = Account("https://example.com", "secret", "users/alice", "Alice", true)
    private lateinit var repository: MemoRepository
    private val memo = Memo("memos/one", "Old", "Old", "PRIVATE", Instant.EPOCH, null,
        Instant.parse("2027-01-01T12:00:00Z"), creator = "users/alice")
    private fun <T> anyValue(): T = any<T>()
    private fun <T> eqValue(value: T): T = eq(value)

    @Before fun setup() {
        `when`(credentials.load()).thenAnswer { stored }
        doAnswer { stored = it.getArgument(0); null }.`when`(credentials).save(anyValue())
        doAnswer { stored = null; null }.`when`(credentials).clear()
        `when`(database.feed()).thenReturn(emptyList())
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
    }

    @Test fun `capability removal cleans before failing feed and reaches observable account`() = runBlocking<Unit> {
        `when`(api.supportsMemoReminderTime("https://example.com", "secret")).thenReturn(false)
        `when`(api.listPage(anyValue(), anyValue(), anyValue(), isNull())).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        assertFalse(repository.accountSummary.value!!.supportsMemoReminderTime)
        val order = inOrder(reminders, api)
        order.verify(reminders).clearReminders()
        order.verify(api).listPage(anyValue(), anyValue(), anyValue(), isNull())
        verify(scheduler, never()).cancel()
    }

    @Test fun `profile failure does not revoke last confirmed capability or discard inventory`() = runBlocking<Unit> {
        `when`(api.supportsMemoReminderTime("https://example.com", "secret")).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        assertTrue(repository.accountSummary.value!!.supportsMemoReminderTime)
        verify(reminders, never()).clearReminders()
        verify(database, never()).replaceFeed(anyValue())
    }

    @Test fun `edit conflict never patches or changes cache`() = runBlocking<Unit> {
        val account = stored!!
        `when`(api.getMemo(account, memo.name)).thenReturn(memo.copy(content = "Changed in web"))
        val error = runCatching { repository.edit(account.summary(), memo, MemoEdit("Draft", "PRIVATE", memo.reminderTime)) }.exceptionOrNull() as AppException
        assertEquals(AppError.Conflict, error.error)
        verify(api, never()).editMemo(anyValue(), anyValue(), anyValue())
        verify(database, never()).replaceFeed(anyValue())
    }

    @Test fun `confirmed delete removes cache and local reminder only after server success`() = runBlocking<Unit> {
        val account = stored!!
        `when`(api.listPage(account, "NORMAL", null)).thenReturn(MemoPage(listOf(memo), null))
        repository.page(account.summary(), false)
        `when`(api.getMemo(account, memo.name)).thenReturn(memo)
        repository.action(account.summary(), memo, "delete")
        assertTrue(repository.feed.value.isEmpty())
        val order = inOrder(api, reminders)
        order.verify(api).deleteMemo(account, memo.name)
        order.verify(reminders).memoDeleted(memo.name)
    }

    @Test fun `failed delete keeps visible memo and reminder`() = runBlocking<Unit> {
        val account = stored!!
        `when`(api.listPage(account, "NORMAL", null)).thenReturn(MemoPage(listOf(memo), null))
        repository.page(account.summary(), false)
        `when`(api.getMemo(account, memo.name)).thenReturn(memo)
        doAnswer { throw AppException(AppError.Server(400)) }.`when`(api).deleteMemo(account, memo.name)
        assertTrue(runCatching { repository.action(account.summary(), memo, "delete") }.exceptionOrNull() is AppException)
        assertEquals(listOf(memo), repository.feed.value)
        verify(reminders, never()).memoDeleted(anyValue())
    }

    @Test fun `space page filters mixed server results and continues pagination`() = runBlocking<Unit> {
        val account = stored!!
        val team = memo.copy(name = "memos/team", space = "spaces/team")
        val other = memo.copy(name = "memos/other", space = null)
        val second = memo.copy(name = "memos/team-two", space = "spaces/team")
        `when`(api.listPage(account, "NORMAL", null, "spaces/team"))
            .thenReturn(MemoPage(listOf(team, other), "next"))
        `when`(api.listPage(account, "NORMAL", "next", "spaces/team"))
            .thenReturn(MemoPage(listOf(second), null))

        repository.page(account.summary(), false, space = "spaces/team")

        assertEquals(listOf(team.name, second.name).toSet(), repository.feed.value.map { it.name }.toSet())
        verify(database, never()).replaceFeed(anyValue())
    }

    @Test fun `refresh preserves client side space scope`() = runBlocking<Unit> {
        val account = stored!!
        val team = memo.copy(name = "memos/team", space = "spaces/team")
        val other = memo.copy(name = "memos/other", space = null)
        var pageCall = 0
        `when`(api.listPage(anyValue(), eqValue("NORMAL"), isNull(), eqValue("spaces/team"))).thenAnswer {
            if (pageCall++ == 0) MemoPage(listOf(team), null) else MemoPage(listOf(team, other), null)
        }
        `when`(api.supportsMemoReminderTime(account.baseUrl, account.token)).thenReturn(true)
        `when`(api.listSpaces(anyValue())).thenReturn(listOf(Space("spaces/team", "Team", "")))
        `when`(api.listAllReminders(anyValue())).thenReturn(emptyList())

        repository.page(account.summary(), false, space = "spaces/team")
        repository.refresh()

        assertEquals(listOf(team), repository.feed.value)
    }

    @Test fun `reminder clear updates cache and coordinator from returned memo`() = runBlocking<Unit> {
        val account = stored!!
        val edit = MemoEdit("New", "PRIVATE", null)
        val updated = memo.copy(content = "New", reminderTime = null)
        `when`(api.getMemo(account, memo.name)).thenReturn(memo)
        `when`(api.editMemo(account, memo, edit)).thenReturn(updated)
        repository.edit(account.summary(), memo, edit)
        assertEquals(updated, repository.feed.value.single())
        verify(reminders).memoChanged(updated)
    }

    @Test fun `logout waits for in flight refresh then clears all results`() = runBlocking<Unit> {
        val account = stored!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(api.supportsMemoReminderTime(account.baseUrl, account.token)).thenAnswer {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        }
        `when`(api.listPage(anyValue(), anyValue(), anyValue(), isNull())).thenReturn(MemoPage(listOf(memo), null))
        `when`(api.listAllReminders(anyValue())).thenReturn(emptyList())
        val refresh = async(Dispatchers.Default) { repository.refresh() }
        check(entered.await(5, TimeUnit.SECONDS))
        val logout = async(Dispatchers.Default) { repository.logout() }
        release.countDown()
        refresh.await(); logout.await()
        assertNull(repository.account())
        assertNull(repository.accountSummary.value)
        assertTrue(repository.feed.value.isEmpty())
        verify(reminders).clearReminders()
        verify(database).clearAll()
    }

    @Test fun `operation carrying old account identity cannot write to new account`() = runBlocking<Unit> {
        val old = stored!!.summary()
        stored = Account("https://another.example", "new-secret", "users/alice", "Alice", true)
        val error = runCatching { repository.create(old, NewMemo("Old draft", null)) }.exceptionOrNull() as AppException
        assertEquals(AppError.Authentication, error.error)
        verify(api, never()).createMemo(anyValue(), anyValue())
    }
}
