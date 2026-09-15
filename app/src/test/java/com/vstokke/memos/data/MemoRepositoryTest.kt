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
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun `content-only task edit preserves metadata and queues exact source`() = runBlocking<Unit> {
        val edited = repository.editContent(stored!!.summary(), memo, "- [x] done")

        assertEquals("- [x] done", edited.content)
        assertEquals(memo.visibility, edited.visibility)
        assertEquals(memo.reminderTime, edited.reminderTime)
        assertEquals(memo.pinned, edited.pinned)
        assertEquals(memo.state, edited.state)
        assertEquals(memo.space, edited.space)
        assertEquals(memo, operations[memo.name]!!.base)
        verify(scheduler).enqueueRepair()
    }

    @Test fun `content-only task edit rejects stale rendered snapshot`() = runBlocking<Unit> {
        rows[memo.name] = memo.copy(content = "Changed locally", visibility = "PUBLIC")

        val error = runCatching {
            repository.editContent(stored!!.summary(), memo, "- [x] stale")
        }.exceptionOrNull() as AppException

        assertEquals(AppError.Conflict, error.error)
        assertTrue(operations.isEmpty())
        assertEquals("Changed locally", rows[memo.name]!!.content)
        verifyNoInteractions(api)
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

    @Test fun `second resource auth failure is visible but does not permanently require sign in`() = runBlocking<Unit> {
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
        val accessTwoAttempts = AtomicInteger()
        `when`(api.supportsMemoReminderTime("https://example.com", "access-two"))
            .thenAnswer { if (accessTwoAttempts.getAndIncrement() == 0) throw AppException(AppError.Authentication) else true }

        val error = runCatching { repository.refresh() }.exceptionOrNull() as AppException

        assertEquals(AppError.ResourceAuthentication, error.error)
        assertFalse(repository.syncState.value.signInRequired)
        assertTrue(repository.syncState.value.failed)
        assertEquals("refresh-two", repository.account()!!.refreshToken)
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")

        repository.refresh()

        assertFalse(repository.syncState.value.signInRequired)
        assertFalse(repository.syncState.value.failed)
        verify(api, times(2)).supportsMemoReminderTime(anyValue(), eqValue("access-two"))
    }

    @Test fun `upload auth failure also gets one refresh retry without an auth latch`() = runBlocking<Unit> {
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
        repository.edit(stored!!.summary(), memo, MemoEdit("Local", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(memo)
        val applyAttempts = AtomicInteger()
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue()))
            .thenAnswer {
                if (applyAttempts.getAndIncrement() < 2) throw AppException(AppError.Authentication)
                it.getArgument<Memo>(2)
            }
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenReturn(SessionTokens("access-two", Instant.now().plusSeconds(900), "refresh-two"))

        val error = runCatching { repository.refresh() }.exceptionOrNull() as AppException

        assertEquals(AppError.ResourceAuthentication, error.error)
        assertFalse(repository.syncState.value.signInRequired)
        assertTrue(repository.syncState.value.failed)
        assertEquals(MemoSyncStatus.PENDING, operations[memo.name]!!.status)
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")
        verify(api, times(2)).applyDesired(anyValue(), anyValue(), anyValue())

        repository.refresh()

        assertTrue(operations.isEmpty())
        assertFalse(repository.syncState.value.signInRequired)
        verify(api, times(3)).applyDesired(anyValue(), anyValue(), anyValue())
    }

    @Test fun `legacy session auth latch recovers and clears after authenticated validation`() = runBlocking<Unit> {
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
        metadata["auth_required"] = "true"
        repository = MemoRepository(credentials, database, api, reminders, scheduler)

        repository.refresh()

        assertFalse(repository.syncState.value.signInRequired)
        assertEquals("false", metadata["auth_required"])
        assertEquals("", metadata["auth_required_reason"])
        verify(api).supportsMemoReminderTime("https://example.com", "access-one")
    }

    @Test fun `failed activation preserves prior account cache owner and local writes`() = runBlocking<Unit> {
        val previous = stored!!
        val replacement = SignedInSession(
            User("users/bob", "bob", "Bob"),
            SessionTokens("new-access", Instant.now().plusSeconds(900), "new-refresh"),
        )
        `when`(api.signInWithPassword(anyValue(), anyValue(), anyValue())).thenReturn(replacement)
        val saveAttempts = AtomicInteger()
        doAnswer {
            stored = it.getArgument(0)
            if (saveAttempts.getAndIncrement() == 0) throw AppException(AppError.CredentialPersistence)
            null
        }.`when`(credentials).save(anyValue())

        val error = runCatching {
            repository.signInWithPassword("https://example.com", "bob", "not-persisted")
        }.exceptionOrNull() as AppException

        assertEquals(AppError.CredentialPersistence, error.error)
        assertEquals(previous.userName, repository.account()!!.userName)
        assertEquals(previous.userName, credentials.load()!!.userName)
        assertEquals("https://example.com" + '\n' + "users/alice", metadata["owner"])
        assertEquals(memo, repository.feed.value.single())
        verify(reminders, never()).clearReminders()
        verify(database, never()).clearAll()
        repository.edit(previous.summary(), memo, MemoEdit("Still local", "PRIVATE", memo.reminderTime))
        assertEquals("Still local", rows[memo.name]!!.content)
        operations.clear()

        // A later successful activation is a consistent owner transition, and logout cannot
        // resurrect the failed account after that transition.
        doAnswer { stored = it.getArgument(0); null }.`when`(credentials).save(anyValue())
        repository.signInWithPassword("https://example.com", "bob", "not-persisted")
        assertEquals("users/bob", repository.account()!!.userName)
        assertTrue(repository.feed.value.isEmpty())
        assertEquals("https://example.com" + '\n' + "users/bob", metadata["owner"])
        repository.logout()
        assertNull(repository.account())
        assertNull(credentials.load())
    }

    @Test fun `failed activation rollback stays pending across store mutation and restoration failure`() = runBlocking<Unit> {
        val previous = stored!!
        val replacement = SignedInSession(
            User("users/bob", "bob", "Bob"),
            SessionTokens("new-access", Instant.now().plusSeconds(900), "new-refresh"),
        )
        `when`(api.signInWithPassword(anyValue(), anyValue(), anyValue())).thenReturn(replacement)
        val saveAttempts = AtomicInteger()
        doAnswer {
            val candidate = it.getArgument<Account>(0)
            when (saveAttempts.getAndIncrement()) {
                0 -> {
                    // Failed B commit updates the store's in-memory view first.
                    stored = candidate
                    throw AppException(AppError.CredentialPersistence)
                }
                1, 2 -> throw AppException(AppError.CredentialPersistence)
                else -> {
                    stored = candidate
                    null
                }
            }
        }.`when`(credentials).save(anyValue())

        val activationError = runCatching {
            repository.signInWithPassword("https://example.com", "bob", "not-persisted")
        }.exceptionOrNull() as AppException

        assertEquals(AppError.CredentialPersistence, activationError.error)
        assertEquals("users/bob", credentials.load()!!.userName)
        assertEquals(previous.userName, repository.account()!!.userName)
        assertEquals("https://example.com" + '\n' + "users/alice", metadata["owner"])
        assertEquals(memo, repository.feed.value.single())
        verify(database, never()).clearAll()
        repository.edit(previous.summary(), memo, MemoEdit("Safe local edit", "PRIVATE", memo.reminderTime))
        assertEquals("Safe local edit", rows[memo.name]!!.content)
        val wrongExpected = previous.summary().copy(userName = "users/bob")
        val identityError = runCatching {
            repository.edit(wrongExpected, rows[memo.name]!!, MemoEdit("Wrong owner", "PRIVATE", memo.reminderTime))
        }.exceptionOrNull() as AppException
        assertEquals(AppError.Authentication, identityError.error)

        val retryError = runCatching { repository.refresh() }.exceptionOrNull() as AppException
        assertEquals(AppError.CredentialPersistence, retryError.error)
        assertFalse(repository.syncState.value.signInRequired)
        // Restoration failed before changing the store, so the pending A save blocks the profile.
        verify(api, never()).supportsMemoReminderTime(anyValue(), anyValue())
        verify(api, never()).refreshSession(anyValue(), anyValue())

        clearInvocations(api, credentials)
        repository.refresh()
        val order = inOrder(credentials, api)
        order.verify(credentials).save(anyValue())
        order.verify(api).supportsMemoReminderTime("https://example.com", "secret")
        assertEquals(previous.userName, credentials.load()!!.userName)
        assertEquals(previous.userName, repository.account()!!.userName)
    }

    @Test fun `failed same-owner reauthentication preserves active account and cache`() = runBlocking<Unit> {
        val previous = stored!!
        val replacement = SignedInSession(
            User("users/alice", "alice", "Alice"),
            SessionTokens("new-access", Instant.now().plusSeconds(900), "new-refresh"),
        )
        `when`(api.signInWithPassword(anyValue(), anyValue(), anyValue())).thenReturn(replacement)
        val saveAttempts = AtomicInteger()
        doAnswer {
            stored = it.getArgument(0)
            if (saveAttempts.getAndIncrement() == 0) throw AppException(AppError.CredentialPersistence)
            null
        }.`when`(credentials).save(anyValue())

        val error = runCatching {
            repository.signInWithPassword("https://example.com", "alice", "not-persisted")
        }.exceptionOrNull() as AppException

        assertEquals(AppError.CredentialPersistence, error.error)
        assertEquals(previous.userName, repository.account()!!.userName)
        assertEquals(previous.userName, credentials.load()!!.userName)
        assertEquals(memo, repository.feed.value.single())
        verify(database, never()).clearAll()
        repository.edit(previous.summary(), memo, MemoEdit("Still local", "PRIVATE", memo.reminderTime))
        assertEquals("Still local", rows[memo.name]!!.content)
    }

    @Test fun `rejected refresh reason survives reconstruction and prevents retry`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenAnswer { throw AppException(AppError.Authentication) }

        val first = runCatching { repository.refresh() }.exceptionOrNull() as AppException

        assertEquals(AppError.SessionRefreshRejected, first.error)
        assertTrue(repository.syncState.value.signInRequired)
        assertEquals("refresh_rejected", metadata["auth_required_reason"])
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        assertTrue(repository.syncState.value.signInRequired)
        assertTrue(runCatching { repository.refresh() }.isFailure)
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")
        assertEquals(memo, repository.feed.value.single())
    }

    @Test fun `transient refresh failure remains recoverable after reconstruction`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenAnswer { throw AppException(AppError.Network) }

        assertEquals(AppError.Network, runCatching { repository.refresh() }.exceptionOrNull()?.let { (it as AppException).error })
        assertFalse(repository.syncState.value.signInRequired)
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        doReturn(SessionTokens("access-two", Instant.now().plusSeconds(900), "refresh-two"))
            .`when`(api).refreshSession("https://example.com", "refresh-one")

        repository.refresh()

        assertFalse(repository.syncState.value.signInRequired)
        assertEquals("refresh-two", repository.account()!!.refreshToken)
    }

    @Test fun `failed credential save is a barrier until newest rotated credential is committed`() = runBlocking<Unit> {
        stored = Account("https://example.com", "", "users/alice", "Alice", true, AuthMethod.SESSION, "refresh-one")
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        val rotated = SessionTokens("access-two", Instant.now().minusSeconds(60), "refresh-two")
        val latest = SessionTokens("access-three", Instant.now().plusSeconds(900), "refresh-three")
        doReturn(rotated).`when`(api).refreshSession("https://example.com", "refresh-one")
        doReturn(latest).`when`(api).refreshSession("https://example.com", "refresh-two")
        val saveAttempts = AtomicInteger()
        doAnswer {
            // Simulate SharedPreferences updating its in-memory values before commit reports
            // failure, rather than modeling only a throw-before-mutation store.
            stored = it.getArgument(0)
            if (saveAttempts.getAndIncrement() < 2) throw AppException(AppError.CredentialPersistence)
            null
        }.`when`(credentials).save(anyValue())

        val first = runCatching { repository.refresh() }.exceptionOrNull() as AppException
        assertEquals(AppError.CredentialPersistence, first.error)
        assertEquals("access-two", repository.account()!!.token)
        verify(api).refreshSession("https://example.com", "refresh-one")
        verify(api, never()).supportsMemoReminderTime(anyValue(), anyValue())

        val second = runCatching { repository.refresh() }.exceptionOrNull() as AppException
        assertEquals(AppError.CredentialPersistence, second.error)
        // The retained access token is expired, but the pending save still blocks another rotation.
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")
        verify(api, never()).refreshSession("https://example.com", "refresh-two")
        verify(api, never()).supportsMemoReminderTime(anyValue(), anyValue())

        clearInvocations(api, credentials)
        repository.refresh()

        val order = inOrder(credentials, api)
        order.verify(credentials).save(anyValue())
        order.verify(api).refreshSession("https://example.com", "refresh-two")
        order.verify(credentials).save(anyValue())
        order.verify(api).supportsMemoReminderTime("https://example.com", "access-three")
        assertEquals("refresh-three", repository.account()!!.refreshToken)
        assertFalse(repository.syncState.value.signInRequired)
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

        assertEquals(AppError.SessionRefreshRejected, error.error)
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

    @Test fun `plain pending upload is not stranded by capability discovery failure`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("Uploaded first", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { it.getArgument<Memo>(2) }
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenAnswer {
            throw AppException(AppError.Network)
        }

        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)

        verify(api).applyDesired(anyValue(), anyValue(), anyValue())
        assertTrue(operations.isEmpty())
        assertNotNull(repository.syncState.value.lastUploadAckAt)
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
    }

    @Test fun `interrupted pull or token cycle never prunes cache`() = runBlocking<Unit> {
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenReturn(MemoPage(listOf(memo), "loop"))
        assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is AppException)
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
        assertEquals(memo, rows[memo.name])
    }

    @Test fun `bounded sync turn reports more work instead of following unique page tokens forever`() = runBlocking<Unit> {
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer {
                val number = pages.incrementAndGet()
                MemoPage(emptyList(), "unique-$number")
            }

        val result = repository.syncTurn()

        assertTrue(result.moreWork)
        assertTrue(pages.get() in 100..128)
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
        assertFalse(repository.syncState.value.syncing)
    }

    @Test fun `resumed bounded scan advances its cursor instead of repeating the first page prefix`() = runBlocking<Unit> {
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer {
                val number = pages.incrementAndGet()
                if (number < 130) MemoPage(emptyList(), "cursor-$number") else MemoPage(emptyList(), null)
            }

        val first = repository.syncTurn()
        val firstCount = pages.get()
        assertTrue(first.moreWork)
        assertEquals(127, firstCount)

        val second = repository.syncTurn()
        assertTrue("the resumed turn must request the page after the saved cursor", pages.get() > firstCount)
        assertTrue(second.pullCompleted)
        assertFalse(second.moreWork)
    }

    @Test fun `conflict and failed rows do not count as eligible more work`() = runBlocking<Unit> {
        operations[memo.name] = PendingMemo(
            desired = memo, base = memo, deleted = false, revision = 1L,
            sent = null, sentDeleted = false, status = MemoSyncStatus.CONFLICT, server = memo,
        )

        val result = repository.syncTurn()

        assertFalse(result.moreWork)
    }

    @Test fun `targeted fence budget resumes at the next fence`() = runBlocking<Unit> {
        val fences = (1..9).map { index ->
            val acknowledged = memo.copy(name = "memos/fence-$index", content = "ack-$index", snippet = "ack-$index")
            SyncFence(acknowledged.name, index.toLong(), acknowledged, deleted = false)
        }
        val scan = ScanSnapshot(0L, fences)
        `when`(database.beginScan()).thenReturn(scan)
        `when`(database.fences()).thenReturn(fences)
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenReturn(MemoPage(emptyList(), null))
        `when`(api.getMemo(anyValue(), anyValue())).thenAnswer { call ->
            fences.first { it.name == call.getArgument<String>(1) }.snapshot
        }

        val first = repository.syncTurn()
        assertTrue(first.moreWork)
        verify(api, times(8)).getMemo(anyValue(), anyValue())

        val second = repository.syncTurn()
        assertTrue(second.pullCompleted)
        verify(api, times(9)).getMemo(anyValue(), anyValue())
    }

    @Test fun `save queued during a feed page is uploaded at the page boundary`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer {
                if (pages.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                MemoPage(emptyList(), null)
            }
        doAnswer {
            val account = it.getArgument<Account>(0)
            val draft = it.getArgument<NewMemo>(1)
            val id = it.getArgument<String>(2)
            CreatedMemoResult(
                Memo("memos/$id", draft.content, draft.content, draft.visibility, Instant.now(), null,
                    draft.reminderTime, account.userName, space = draft.space),
                true,
            )
        }.`when`(api).createMemo(anyValue(), anyValue(), anyValue())

        val sync = async(Dispatchers.Default) { repository.syncTurn() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val queued = repository.create(stored!!.summary(), NewMemo("mid-page", null)).memo
        release.countDown()
        val result = sync.await()

        assertTrue(result.pushProgress)
        assertTrue(operations.isEmpty())
        verify(api).createMemo(anyValue(), anyValue(), eqValue(queued.name.substringAfter('/')))
    }

    @Test fun `upload budget counts selected attempts and yields before pulling`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer {
                val page = pages.incrementAndGet()
                if (page == 40) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                MemoPage(emptyList(), "page-$page".takeIf { page < 41 })
            }
        doAnswer {
            val account = it.getArgument<Account>(0)
            val draft = it.getArgument<NewMemo>(1)
            val id = it.getArgument<String>(2)
            CreatedMemoResult(
                Memo("memos/$id", draft.content, draft.content, draft.visibility, Instant.now(), null,
                    draft.reminderTime, account.userName, space = draft.space), true,
            )
        }.`when`(api).createMemo(anyValue(), anyValue(), anyValue())

        val sync = async(Dispatchers.Default) { repository.syncTurn() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val queued = repository.create(stored!!.summary(), NewMemo("after-budget", null)).memo
        release.countDown()
        val result = sync.await()

        assertTrue(result.pushProgress)
        assertTrue(operations.isEmpty())
        verify(api).createMemo(anyValue(), anyValue(), eqValue(queued.name.substringAfter('/')))
    }

    @Test fun `completed page cursor survives a boundary upload failure`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val normalPages = AtomicInteger()
        val applyAttempts = AtomicInteger()
        val archivedSeen = AtomicInteger()
        `when`(database.beginScan()).thenReturn(ScanSnapshot(0L, emptyList()))
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer { call ->
                val state = call.getArgument<String>(1)
                val token = call.getArgument<String?>(2)
                when {
                    state == "NORMAL" && token == null -> {
                        normalPages.incrementAndGet()
                        MemoPage(emptyList(), "normal-next")
                    }
                    state == "NORMAL" && token == "normal-next" -> {
                        normalPages.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        MemoPage(emptyList(), null)
                    }
                    state == "ARCHIVED" && token == null -> {
                        archivedSeen.incrementAndGet()
                        MemoPage(emptyList(), null)
                    }
                    else -> error("unexpected page state=$state token=$token")
                }
            }
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { call ->
            if (applyAttempts.incrementAndGet() == 1) throw AppException(AppError.Network)
            call.getArgument<Memo>(2)
        }

        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = syncScope.async { repository.syncTurn() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repository.edit(stored!!.summary(), memo, MemoEdit("queued", memo.visibility, memo.reminderTime))
        release.countDown()
        assertTrue(runCatching { sync.await() }.isFailure)
        syncScope.cancel()

        val resumedOutcome = runCatching { repository.syncTurn() }
        val resumed = resumedOutcome.getOrThrow()
        assertTrue(resumed.pullCompleted)
        assertEquals(2, normalPages.get())
        assertEquals(1, archivedSeen.get())
        assertTrue(operations.isEmpty())
    }

    @Test fun `spaces failure happens after pending push and cannot gate it`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("pushed", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { it.getArgument<Memo>(2) }
        doAnswer { throw AppException(AppError.Network) }.`when`(api).listSpacesPage(anyValue(), nullable(String::class.java), anyInt())

        assertTrue(runCatching { repository.refresh() }.isFailure)

        val order = inOrder(api)
        order.verify(api).getMemo(anyValue(), eqValue(memo.name))
        order.verify(api).applyDesired(anyValue(), anyValue(), anyValue())
        order.verify(api).supportsMemoReminderTime(anyValue(), anyValue())
        order.verify(api).listSpacesPage(anyValue(), nullable(String::class.java), anyInt())
        assertTrue(operations.isEmpty())
        assertEquals(AppError.Network, repository.syncState.value.pullError)
    }

    @Test fun `feed page failure happens after pending push and leaves scan uncommitted`() = runBlocking<Unit> {
        repository.edit(stored!!.summary(), memo, MemoEdit("pushed", "PRIVATE", memo.reminderTime))
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(memo)
        `when`(api.applyDesired(anyValue(), anyValue(), anyValue())).thenAnswer { it.getArgument<Memo>(2) }
        `when`(api.listSpacesPage(anyValue(), nullable(String::class.java), anyInt()))
            .thenReturn(com.vstokke.memos.domain.SpacePage(emptyList(), null))
        doAnswer { throw AppException(AppError.Network) }.`when`(api)
            .listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt())

        assertTrue(runCatching { repository.refresh() }.isFailure)

        val order = inOrder(api)
        order.verify(api).getMemo(anyValue(), eqValue(memo.name))
        order.verify(api).applyDesired(anyValue(), anyValue(), anyValue())
        order.verify(api).supportsMemoReminderTime(anyValue(), anyValue())
        order.verify(api).listSpacesPage(anyValue(), nullable(String::class.java), anyInt())
        order.verify(api).listPage(anyValue(), eqValue("NORMAL"), isNull(), isNull(), eqValue(1_000))
        assertTrue(operations.isEmpty())
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
    }

    @Test fun `stale fenced list item is revalidated and passed to atomic scan merge`() = runBlocking<Unit> {
        val acknowledged = memo.copy(content = "acknowledged", updateTime = Instant.now())
        val stale = memo.copy(content = "stale list")
        val fence = SyncFence(memo.name, 4L, acknowledged, deleted = false)
        val scan = ScanSnapshot(0L, listOf(fence))
        `when`(database.beginScan()).thenReturn(scan)
        `when`(database.fences()).thenReturn(listOf(fence))
        `when`(api.listSpacesPage(anyValue(), nullable(String::class.java), anyInt()))
            .thenReturn(com.vstokke.memos.domain.SpacePage(emptyList(), null))
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer { if (pages.incrementAndGet() == 1) MemoPage(listOf(stale), null) else MemoPage(emptyList(), null) }
        `when`(api.getMemo(anyValue(), eqValue(memo.name))).thenReturn(acknowledged)

        val result = repository.syncTurn()

        assertTrue(result.pullCompleted)
        verify(api, times(1)).getMemo(anyValue(), eqValue(memo.name))
        verify(database).applyScan(
            anyValue(), anyValue(), anyLong(), eqValue(false), anyValue(), eqValue(scan),
            eqValue(mapOf(memo.name to acknowledged)),
        )
    }

    @Test fun `cancelled scan leaves a newly queued outbox row and logout clears it`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(api.listSpacesPage(anyValue(), nullable(String::class.java), anyInt())).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw CancellationException("test cancellation")
        }
        val sync = launch(Dispatchers.Default) { repository.syncTurn() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repository.create(stored!!.summary(), NewMemo("cancelled", null))
        sync.cancel()
        release.countDown()
        sync.join()
        assertTrue(operations.isNotEmpty())
        verify(database, never()).applyScan(anyValue(), anyValue(), anyLong(), anyBoolean(), anyValue())
        repository.logout()
        assertTrue(operations.isEmpty())
        assertNull(repository.account())
        verify(database).clearAll()
    }

    @Test fun `resource authentication rotates once across a resumed page turn`() = runBlocking<Unit> {
        stored = Account(
            "https://example.com", "access-one", "users/alice", "Alice", true,
            AuthMethod.SESSION, "refresh-one", Instant.now().plusSeconds(900),
        )
        repository = MemoRepository(credentials, database, api, reminders, scheduler)
        val pageAttempts = AtomicInteger()
        `when`(api.supportsMemoReminderTime(anyValue(), anyValue())).thenReturn(true)
        `when`(api.refreshSession("https://example.com", "refresh-one"))
            .thenReturn(SessionTokens("access-two", Instant.now().plusSeconds(900), "refresh-two"))
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer {
                if (pageAttempts.incrementAndGet() <= 2) throw AppException(AppError.Authentication)
                MemoPage(emptyList(), null)
            }

        val first = runCatching { repository.syncTurn() }.exceptionOrNull() as AppException

        assertEquals(AppError.ResourceAuthentication, first.error)
        assertFalse(repository.syncState.value.signInRequired)
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")
        val resumed = repository.syncTurn()
        assertTrue(resumed.pullCompleted)
        verify(api, times(1)).refreshSession("https://example.com", "refresh-one")
    }

    @Test fun `matching fenced list item retires without targeted GET`() = runBlocking<Unit> {
        val acknowledged = memo.copy(content = "acknowledged", updateTime = Instant.now())
        val fence = SyncFence(memo.name, 5L, acknowledged, deleted = false)
        val scan = ScanSnapshot(0L, listOf(fence))
        `when`(database.beginScan()).thenReturn(scan)
        `when`(database.fences()).thenReturn(listOf(fence))
        `when`(api.listSpacesPage(anyValue(), nullable(String::class.java), anyInt()))
            .thenReturn(com.vstokke.memos.domain.SpacePage(emptyList(), null))
        val pages = AtomicInteger()
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenAnswer { if (pages.incrementAndGet() == 1) MemoPage(listOf(acknowledged), null) else MemoPage(emptyList(), null) }

        repository.syncTurn()

        verify(api, never()).getMemo(anyValue(), anyValue())
        verify(database).applyScan(
            anyValue(), anyValue(), anyLong(), eqValue(false), anyValue(), eqValue(scan),
            eqValue(mapOf(memo.name to acknowledged)),
        )
    }

    @Test fun `complete absence corroborates deletion fence without GET`() = runBlocking<Unit> {
        val fence = SyncFence(memo.name, 6L, null, deleted = true)
        val scan = ScanSnapshot(0L, listOf(fence))
        `when`(database.beginScan()).thenReturn(scan)
        `when`(database.fences()).thenReturn(listOf(fence))
        `when`(api.listSpacesPage(anyValue(), nullable(String::class.java), anyInt()))
            .thenReturn(com.vstokke.memos.domain.SpacePage(emptyList(), null))
        `when`(api.listPage(anyValue(), anyValue(), nullable(String::class.java), nullable(String::class.java), anyInt()))
            .thenReturn(MemoPage(emptyList(), null))

        repository.syncTurn()

        verify(api, never()).getMemo(anyValue(), anyValue())
        verify(database).applyScan(
            anyValue(), anyValue(), anyLong(), eqValue(false), anyValue(), eqValue(scan),
            eqValue(mapOf<String, Memo?>(memo.name to null)),
        )
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
