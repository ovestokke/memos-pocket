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
    private val rows = linkedMapOf<String, Memo>()
    private val operations = linkedMapOf<String, PendingMemo>()
    private val metadata = mutableMapOf("owner" to "https://example.com\nusers/alice")
    private fun <T> anyValue(): T = any<T>()
    private fun <T> eqValue(value: T): T = eq(value)

    @Before fun setup() {
        `when`(credentials.load()).thenAnswer { stored }
        doAnswer { stored = it.getArgument(0); null }.`when`(credentials).save(anyValue())
        doAnswer { stored = null; null }.`when`(credentials).clear()
        `when`(database.metadata(anyValue())).thenAnswer { metadata[it.getArgument<String>(0)] }
        doAnswer { metadata[it.getArgument(0)] = it.getArgument(1); null }.`when`(database).setMetadata(anyValue(), anyValue())
        `when`(database.spaces()).thenReturn(listOf(Space("spaces/team", "Team", "")))
        `when`(database.pending()).thenAnswer { operations.values.toList() }
        `when`(database.localMemos(anyBoolean())).thenAnswer { call -> rows.values.mapNotNull { memo ->
            val op = operations[memo.name]
            if (!call.getArgument<Boolean>(0) && op?.deleted == true) null else memo.copy(syncStatus = op?.status ?: MemoSyncStatus.SYNCED)
        } }
        doAnswer { call ->
            val desired = call.getArgument<Memo>(0); val old = operations[desired.name]
            rows[desired.name] = desired
            operations[desired.name] = PendingMemo(desired, if (old != null) old.base else call.getArgument(1),
                call.getArgument(2), (old?.revision ?: 0) + 1, old?.sent, old?.sentDeleted ?: false, MemoSyncStatus.PENDING, old?.server)
            null
        }.`when`(database).queue(anyValue(), nullable(Memo::class.java), anyBoolean())
        `when`(database.markSent(anyValue())).thenAnswer {
            val sent = it.getArgument<PendingMemo>(0); val current = operations[sent.desired.name]
            if (current == null || current.revision != sent.revision) false else {
                operations[sent.desired.name] = current.copy(sent = sent.desired, sentDeleted = sent.deleted); true
            }
        }
        doAnswer {
            val name = it.getArgument<String>(0)
            operations[name]?.let { op -> operations[name] = op.copy(status = it.getArgument(1), server = it.getArgument(2)) }
            null
        }.`when`(database).markIssue(anyValue(), anyValue(), nullable(Memo::class.java))
        `when`(database.retry(anyValue())).thenAnswer {
            val name = it.getArgument<String>(0)
            val operation = operations[name]
            if (operation?.status != MemoSyncStatus.FAILED) false else {
                operations[name] = operation.copy(
                    status = MemoSyncStatus.PENDING,
                    sent = if (operation.base == null) null else operation.sent,
                    sentDeleted = if (operation.base == null) false else operation.sentDeleted,
                )
                true
            }
        }
        doAnswer {
            val sent = it.getArgument<PendingMemo>(0); val server = it.getArgument<Memo?>(1)
            val current = operations[sent.desired.name]!!
            if (current.revision == sent.revision) {
                operations.remove(sent.desired.name)
                if (server == null) rows.remove(sent.desired.name) else rows[sent.desired.name] = server
            } else operations[sent.desired.name] = current.copy(base = server, sent = null)
            null
        }.`when`(database).acknowledge(anyValue(), nullable(Memo::class.java))
        doAnswer { rows.clear(); operations.clear(); metadata.clear(); null }.`when`(database).clearAll()
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenReturn(true)
        `when`(api.listSpaces(anyValue())).thenReturn(emptyList())
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenReturn(MemoPage(emptyList(), null))
        rows[memo.name] = memo
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
    }

    @Test fun `offline cold start can read feed archive space and detail without auth refresh`() = runBlocking<Unit> {
        stored = Account(stored!!.baseUrl, "", memo.creator, "Alice", true, AuthMethod.SESSION, "refresh")
        val archived = memo.copy(name = "memos/archived", state = "ARCHIVED", space = "spaces/team")
        rows[archived.name] = archived
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        assertEquals(listOf(memo), repository.feed.value)
        repository.page(stored!!.summary(), true, space = "spaces/team")
        assertEquals(listOf(archived), repository.feed.value)
        assertEquals(archived, repository.get(stored!!.summary(), archived.name))
        verifyNoInteractions(api)
    }

    @Test fun `local edit and explicit delete queue even with expired session`() = runBlocking<Unit> {
        val account = stored!!
        val edited = repository.edit(account.summary(), memo, MemoEdit("New", "PRIVATE", null))
        assertEquals("New", rows[memo.name]!!.content)
        assertEquals(memo, operations[memo.name]!!.base)
        repository.action(account.summary(), edited, "delete")
        assertTrue(operations[memo.name]!!.deleted)
        assertTrue(repository.feed.value.isEmpty())
        verify(api, never()).deleteMemo(anyValue(), anyValue())
        verify(scheduler, times(2)).enqueueRepair()
    }

    @Test fun `edit conflict never patches or changes cache`() = runBlocking<Unit> {
        rows[memo.name] = memo.copy(content = "Changed locally")
        val error = runCatching { repository.edit(stored!!.summary(), memo, MemoEdit("Draft", "PRIVATE", null)) }.exceptionOrNull() as AppException
        assertEquals(AppError.Conflict, error.error)
        assertTrue(operations.isEmpty())
        verifyNoInteractions(api)
    }

    @Test fun `confirmed delete removes tombstone only after server success`() = runBlocking<Unit> {
        repository.action(stored!!.summary(), memo, "delete")
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(memo)
        repository.refresh()
        assertTrue(operations.isEmpty())
        assertFalse(rows.containsKey(memo.name))
        verify(api).deleteMemo(anyValue(), eqValue(memo.name))
    }

    @Test fun `failed delete keeps durable discoverable tombstone`() = runBlocking<Unit> {
        repository.action(stored!!.summary(), memo, "delete")
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(memo)
        doAnswer { throw AppException(AppError.Server(400)) }.`when`(api).deleteMemo(anyValue(), anyValue())
        repository.refresh()
        assertTrue(operations[memo.name]!!.deleted)
        assertEquals(MemoSyncStatus.FAILED, repository.syncIssues.value.single().status)
        assertEquals(memo.content, repository.syncIssues.value.single().desired.content)
    }

    @Test fun `space scan filters mixed results and scans archive and every page`() = runBlocking<Unit> {
        val team = memo.copy(name = "memos/team", space = "spaces/team")
        `when`(api.listSpaces(anyValue())).thenReturn(listOf(Space("spaces/team", "Team", "")))
        `when`(api.listPage(anyValue(), eqValue("NORMAL"), isNull(), eqValue("spaces/team"), eqValue(1_000)))
            .thenReturn(MemoPage(listOf(team, memo.copy(name = "memos/wrong", creator = "users/other")), "next"))
        `when`(api.listPage(anyValue(), eqValue("NORMAL"), eqValue("next"), eqValue("spaces/team"), eqValue(1_000)))
            .thenReturn(MemoPage(emptyList(), null))
        repository.refresh()
        verify(database).applyScan(eqValue(listOf(team)), anyValue(), anyLong(), eqValue(false), anyValue())
        verify(api).listPage(anyValue(), eqValue("ARCHIVED"), isNull(), eqValue("spaces/team"), eqValue(1_000))
    }

    @Test fun `refresh does not change selected local space scope`() = runBlocking<Unit> {
        val team = memo.copy(name = "memos/team", space = "spaces/team")
        rows[team.name] = team
        repository.page(stored!!.summary(), false, space = "spaces/team")
        repository.refresh()
        assertEquals(listOf(team), repository.feed.value)
    }

    @Test fun `reminder clear uses local overlay`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("New", "PRIVATE", null))
        verify(reminders).reconcile(emptyList())
    }

    @Test fun `legacy auth failure retains account cache outbox and exact draft`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Draft", "PRIVATE", memo.reminderTime))
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Authentication) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        assertTrue(repository.syncState.value.signInRequired)
        assertNotNull(repository.account())
        assertEquals("Draft", repository.feed.value.single().content)
        verify(database, never()).clearAll()
        verify(credentials, never()).clear()
    }

    @Test fun `rejected session access token refreshes once and retries sync`() = runBlocking<Unit> {
        stored = Account(
            "https://example.com",
            "access-one",
            "users/alice",
            "Alice",
            true,
            AuthMethod.SESSION,
            "refresh-one",
            Instant.now().plusSeconds(900),
        )
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.supportsMemoReminderTime("https://example.com", "access-one"))
            .thenAnswer { throw AppException(AppError.Authentication) }
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenReturn(SessionTokens("access-two", Instant.now().plusSeconds(900), "refresh-two"))
        `when`(api.supportsMemoReminderTime("https://example.com", "access-two")).thenReturn(true)

        repository.refresh()

        assertFalse(repository.syncState.value.signInRequired)
        assertEquals("access-two", repository.account()!!.token)
        assertEquals("refresh-two", repository.account()!!.refreshToken)
        val order = inOrder(api, credentials)
        order.verify(api).supportsMemoReminderTime("https://example.com", "access-one")
        order.verify(api).refreshSession("https://example.com", "refresh-one")
        order.verify(credentials).save(anyValue())
        order.verify(api).supportsMemoReminderTime("https://example.com", "access-two")
    }

    @Test fun `offline refresh failure keeps session and does not ask for sign in`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenAnswer { throw AppException(AppError.Network) }

        val error = runCatching { repository.refresh() }.exceptionOrNull() as AppException

        assertEquals(AppError.Network, error.error)
        assertFalse(repository.syncState.value.signInRequired)
        assertTrue(repository.syncState.value.failed)
        assertNotNull(repository.account())
        verify(database, never()).setMetadata("auth_required", "true")
    }

    @Test fun `rejected refresh token pauses sync without deleting local data`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenAnswer { throw AppException(AppError.Authentication) }

        val error = runCatching { repository.refresh() }.exceptionOrNull() as AppException

        assertEquals(AppError.Authentication, error.error)
        assertTrue(repository.syncState.value.signInRequired)
        assertEquals(memo, repository.feed.value.single())
        verify(database, never()).clearAll()
        verify(credentials, never()).clear()
    }

    @Test fun `profile failure preserves last confirmed capability and inventory`() = runBlocking<Unit> {
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        assertTrue(repository.accountSummary.value!!.supportsMemoReminderTime)
        verify(reminders, never()).clearReminders()
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
    }

    @Test fun `interrupted pull or token cycle never prunes cache`() = runBlocking<Unit> {
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenReturn(MemoPage(listOf(memo), "loop"))
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
        assertEquals(memo, rows[memo.name])
    }

    @Test fun `operation carrying old account identity cannot write to new account`() = runBlocking<Unit> {
        val old = stored!!.summary()
        stored = Account("https://another.example", "new-secret", "users/alice", "Alice", true)
        val error = runCatching { repository.create(old, NewMemo("Old draft", null)) }.exceptionOrNull() as AppException
        assertEquals(AppError.Authentication, error.error)
        assertTrue(operations.isEmpty())
    }

    @Test fun `stored session refreshes and persists rotation before pulling but PAT does not`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenReturn(SessionTokens("access-two", Instant.now().plusSeconds(900), "refresh-two"))
        repository.refresh()
        assertEquals("refresh-two", repository.account()!!.refreshToken)
        val order = inOrder(api, credentials)
        order.verify(api).refreshSession("https://example.com", "refresh-one")
        order.verify(credentials).save(anyValue())
        order.verify(api).supportsMemoReminderTime("https://example.com", "access-two")
    }

    @Test fun `stable create recovers lost response with GET without reposting`() = runBlocking<Unit> {
        val created = repository.create(stored!!.summary(), NewMemo("New", null)).memo.serverFields()
        assertTrue(created.name.matches(Regex("memos/mp-[a-f0-9]{32}")))
        `when`(api.getMemo(anyValue(), eqValue(created.name))).thenAnswer { throw AppException(AppError.Server(404)) }
        `when`(api.createMemo(anyValue(), anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        assertNotNull(operations[created.name]!!.sent)
        `when`(api.getMemo(anyValue(), eqValue(created.name))).thenReturn(created)
        repository.refresh()
        assertFalse(operations.containsKey(created.name))
        verify(api, times(1)).createMemo(anyValue(), anyValue(), eqValue(created.name.substringAfter('/')))
    }

    @Test fun `uncertain create is not reposted until explicit retry`() = runBlocking<Unit> {
        val created = repository.create(stored!!.summary(), NewMemo("New", null)).memo.serverFields()
        `when`(api.getMemo(anyValue(), eqValue(created.name))).thenAnswer { throw AppException(AppError.Server(404)) }
        `when`(api.createMemo(anyValue(), anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Network) }

        assertTrue(runCatching { repository.refresh() }.isFailure)
        repository.refresh()

        assertEquals(MemoSyncStatus.FAILED, operations.getValue(created.name).status)
        verify(api, times(1)).createMemo(anyValue(), anyValue(), anyValue())

        repository.retryFailed(stored!!.summary(), created.name)
        doReturn(CreatedMemoResult(created, true)).`when`(api).createMemo(anyValue(), anyValue(), anyValue())
        repository.refresh()
        assertFalse(operations.containsKey(created.name))
        verify(api, times(2)).createMemo(anyValue(), anyValue(), anyValue())
    }

    @Test fun `server that ignores chosen create ID becomes a conflict`() = runBlocking<Unit> {
        val created = repository.create(stored!!.summary(), NewMemo("New", null)).memo.serverFields()
        val unexpected = created.copy(name = "memos/server-generated")
        `when`(api.getMemo(anyValue(), eqValue(created.name))).thenAnswer { throw AppException(AppError.Server(404)) }
        `when`(api.createMemo(anyValue(), anyValue(), anyValue())).thenReturn(CreatedMemoResult(unexpected, true))

        repository.refresh()

        val issue = operations.getValue(created.name)
        assertEquals(MemoSyncStatus.CONFLICT, issue.status)
        assertEquals(unexpected, issue.server)
        verify(api, times(1)).createMemo(anyValue(), anyValue(), anyValue())
    }

    @Test fun `offline create then pin and archive converges through normalized create and patch`() = runBlocking<Unit> {
        val created = repository.create(stored!!.summary(), NewMemo("New", null)).memo
        val pinned = repository.action(stored!!.summary(), created, "pin")!!
        val archived = repository.action(stored!!.summary(), pinned, "archive")!!
        val remote = created.serverFields()
        `when`(api.getMemo(anyValue(), eqValue(created.name)))
            .thenAnswer { throw AppException(AppError.Server(404)) }
            .thenReturn(remote)
        `when`(api.createMemo(anyValue(), anyValue(), anyValue())).thenReturn(CreatedMemoResult(remote, true))
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { it.getArgument<Memo>(2) }
        repository.refresh()
        assertFalse(operations.containsKey(created.name))
        assertEquals(archived.serverFields(), rows[created.name]!!.serverFields())
        verify(api, times(1)).createMemo(anyValue(), anyValue(), anyValue())
        verify(api, times(1)).applyDesired(anyValue(), anyValue(), anyValue())
    }

    @Test fun `remote divergence keeps both versions and does not block unrelated memo`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Local", "PRIVATE", memo.reminderTime))
        val another = memo.copy(name = "memos/two")
        rows[another.name] = another
        repository.edit(stored!!.summary(), another, MemoEdit("Other local", "PRIVATE", another.reminderTime))
        val remote = memo.copy(content = "Web")
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(remote)
        `when`(api.getMemo(anyValue(), eqValue(another.name))).thenReturn(another)
        `when`(api.applyDesired(anyValue(), eqValue(another), anyValue())).thenAnswer { it.getArgument<Memo>(2) }
        repository.refresh()
        assertEquals("Local", operations[memo.name]!!.desired.content)
        assertEquals(remote, operations[memo.name]!!.server)
        assertEquals(MemoSyncStatus.CONFLICT, operations[memo.name]!!.status)
        assertFalse(operations.containsKey(another.name))
    }

    @Test fun `acknowledged metadata does not invalidate a still open editor`() = runBlocking<Unit> {
        val openEditorBase = repository.edit(stored!!.summary(), memo, MemoEdit("First", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer {
            it.getArgument<Memo>(2).copy(updateTime = Instant.now(), snippet = "rendered")
        }
        repository.refresh()
        val second = repository.edit(stored!!.summary(), openEditorBase, MemoEdit("Second", "PRIVATE", memo.reminderTime))
        assertEquals("Second", second.content)
        assertEquals("First", operations[memo.name]!!.base!!.content)
        assertNotNull(operations[memo.name]!!.base!!.updateTime)
    }

    @Test fun `local save is not blocked by network and acknowledgement cannot overwrite it`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("First", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(memo)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); it.getArgument<Memo>(2)
        }
        val sync = async(Dispatchers.Default) { repository.refresh() }
        check(entered.await(5, TimeUnit.SECONDS))
        val second = withTimeout(2000) { repository.edit(stored!!.summary(), repository.feed.value.single(),
            MemoEdit("Second", "PRIVATE", memo.reminderTime)) }
        release.countDown(); sync.await()
        assertEquals("Second", repository.feed.value.single().content)
        assertEquals("First", operations[memo.name]!!.base!!.content)
        assertEquals(second.content, operations[memo.name]!!.desired.content)
    }

    @Test fun `failed memo can be explicitly retried without losing frozen request`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Local", "PRIVATE", memo.reminderTime))
        val operation = operations.getValue(memo.name)
        operations[memo.name] = operation.copy(status = MemoSyncStatus.FAILED, sent = operation.desired)

        repository.retryFailed(stored!!.summary(), memo.name)

        assertEquals(MemoSyncStatus.PENDING, operations.getValue(memo.name).status)
        assertEquals("Local", operations.getValue(memo.name).sent!!.content)
        verify(scheduler, atLeastOnce()).enqueueRepair()
    }

    @Test fun `reauthentication resumes same owner but cannot replace pending work`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Local", "PRIVATE", memo.reminderTime))
        val session = SignedInSession(User("users/alice", "alice", "Alice"),
            SessionTokens("new-access", Instant.now().plusSeconds(900), "new-refresh"))
        `when`(api.signInWithPassword(anyValue(), anyValue(), anyValue())).thenReturn(session)
        repository.signInWithPassword("https://example.com", "alice", "not-persisted")
        assertEquals("Local", operations[memo.name]!!.desired.content)
        assertEquals("new-refresh", stored!!.refreshToken)
        verify(database, never()).clearAll()
        `when`(api.signInWithPassword(anyValue(), anyValue(), anyValue())).thenReturn(session.copy(user = User("users/bob", "bob", "Bob")))
        val error = runCatching { repository.signInWithPassword("https://example.com", "bob", "not-persisted") }.exceptionOrNull() as AppException
        assertEquals(AppError.PendingAccount, error.error)
        assertEquals("users/alice", stored!!.userName)
        assertEquals("Local", operations[memo.name]!!.desired.content)
    }

    @Test fun `ambiguous patch and delete recover without repeating network mutation`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Local", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        val sent = operations[memo.name]!!.sent!!
        `when`(api.getMemo(anyValue(), anyValue())).thenReturn(sent.copy(updateTime = Instant.now()))
        repository.refresh()
        assertTrue(operations.isEmpty())
        verify(api, times(1)).applyDesired(anyValue(), anyValue(), anyValue())
        val local = repository.feed.value.single()
        repository.action(stored!!.summary(), local, "delete")
        doAnswer { throw AppException(AppError.Network) }.`when`(api).deleteMemo(anyValue(), anyValue())
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        `when`(api.getMemo(anyValue(), anyValue())).thenAnswer { throw AppException(AppError.Server(404)) }
        repository.refresh()
        assertTrue(operations.isEmpty())
        verify(api, times(1)).deleteMemo(anyValue(), anyValue())
    }

    @Test fun `PAT sync does not refresh session and confirmed capability removal precedes failed pull`() = runBlocking<Unit> {
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenReturn(false)
        `when`(api.listSpaces(anyValue())).thenAnswer { throw AppException(AppError.Network) }
        assertTrue(runCatching { repository.refresh() }.isFailure)
        assertFalse(repository.accountSummary.value!!.supportsMemoReminderTime)
        val order = inOrder(api, reminders)
        order.verify(reminders).clearReminders()
        order.verify(api).listSpaces(anyValue())
        verify(api, never()).refreshSession(anyValue(), anyValue())
    }

    @Test fun `logout waits for in flight sync then explicitly clears results`() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenAnswer {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        }
        val sync = async(Dispatchers.Default) { repository.refresh() }
        check(entered.await(5, TimeUnit.SECONDS))
        val logout = async(Dispatchers.Default) { repository.logout() }
        release.countDown(); sync.await(); logout.await()
        assertNull(repository.account()); assertTrue(repository.feed.value.isEmpty())
        verify(database).clearAll()
    }
}
