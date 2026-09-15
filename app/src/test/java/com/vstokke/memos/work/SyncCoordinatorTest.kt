package com.vstokke.memos.work

import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.data.SyncTurnResult
import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private val repository = mock(MemoRepository::class.java)
    private val scheduler = mock(SyncScheduler::class.java)
    private val account = Account("https://example.com", "token", "users/alice", "Alice")
    private lateinit var connectivity: FakeConnectivity
    private val coordinators = mutableListOf<SyncCoordinator>()

    @Before
    fun setup() {
        org.mockito.Mockito.`when`(repository.account()).thenReturn(account)
        connectivity = FakeConnectivity()
    }

    @After
    fun teardown() {
        coordinators.forEach(SyncCoordinator::close)
    }

    @Test
    fun `manual request drives a turn without waiting for WorkManager`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher) {
            calls.incrementAndGet()
            SyncTurnResult(account)
        }

        coordinator.requestManual()
        runCurrent()

        assertEquals(1, calls.get())
        verify(scheduler).enqueueRepair()
    }

    @Test
    fun `signals during a turn cause one coalesced follow-up turn`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher) {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            }
            SyncTurnResult(account)
        }

        coordinator.requestLocalWrite()
        runCurrent()
        entered.await()
        coordinator.requestLocalWrite()
        coordinator.requestManual()
        coordinator.requestLocalWrite()
        runCurrent()
        assertEquals(1, calls.get())

        release.complete(Unit)
        runCurrent()
        assertEquals(2, calls.get())
    }

    @Test
    fun `more work drains in process when WorkManager does not execute`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            if (calls.incrementAndGet() == 1) SyncTurnResult(account, moreWork = true)
            else SyncTurnResult(account, pullCompleted = true)
        }

        coordinator.requestManual()
        runCurrent()

        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun `more work beyond the inline burst cap self wakes with inert WorkManager`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            if (calls.incrementAndGet() <= 10) SyncTurnResult(account, moreWork = true)
            else SyncTurnResult(account)
        }

        coordinator.requestManual()
        advanceUntilIdle()

        assertEquals(11, calls.get())
        coordinator.close()
    }

    @Test
    fun `continuation cooldown waits for virtual time without a new wake`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            if (calls.incrementAndGet() <= 9) SyncTurnResult(account, moreWork = true)
            else SyncTurnResult(account)
        }

        coordinator.requestManual()
        runCurrent()
        assertEquals(9, calls.get())
        advanceTimeBy(99L)
        runCurrent()
        assertEquals(9, calls.get())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(10, calls.get())
    }

    @Test
    fun `local and manual requests during cap-reaching turn skip continuation cooldown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        listOf(SyncWakeReason.LOCAL_WRITE, SyncWakeReason.MANUAL).forEach { signal ->
            val calls = AtomicInteger()
            val turnNineEntered = CompletableDeferred<Unit>()
            val releaseTurnNine = CompletableDeferred<Unit>()
            val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
                val call = calls.incrementAndGet()
                if (call == 9) {
                    turnNineEntered.complete(Unit)
                    releaseTurnNine.await()
                }
                if (call <= 10) SyncTurnResult(account, moreWork = true)
                else SyncTurnResult(account)
            }

            coordinator.requestManual()
            runCurrent()
            turnNineEntered.await()
            coordinator.request(signal)
            releaseTurnNine.complete(Unit)
            runCurrent()

            assertEquals(signal.toString(), 10, calls.get())
            coordinator.close()
        }
    }

    @Test
    fun `local write during continuation sleep interrupts the timer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val cooldownStarted = CompletableDeferred<Unit>()
        val coordinator = makeCoordinator(
            dispatcher,
            now = { testScheduler.currentTime },
            wait = { cooldownStarted.complete(Unit); awaitCancellation() },
        ) {
            if (calls.incrementAndGet() <= 9) SyncTurnResult(account, moreWork = true)
            else SyncTurnResult(account)
        }

        coordinator.requestManual()
        runCurrent()
        cooldownStarted.await()
        assertEquals(9, calls.get())
        coordinator.requestLocalWrite()
        runCurrent()
        assertEquals(10, calls.get())
    }

    @Test
    fun `pull progress prevents continuation no-progress escalation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val coordinator = makeCoordinator(
            dispatcher,
            now = { testScheduler.currentTime },
            wait = { waits += it; delay(it) },
        ) {
            if (calls.incrementAndGet() <= 18) {
                SyncTurnResult(account, moreWork = true, pullProgress = true)
            } else SyncTurnResult(account)
        }

        coordinator.requestManual()
        advanceUntilIdle()

        assertEquals(19, calls.get())
        assertTrue(waits.isNotEmpty())
        assertTrue(waits.all { it == 100L })
    }

    @Test
    fun `perpetual no progress more work is rate limited after the burst`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val coordinator = makeCoordinator(
            dispatcher,
            now = { testScheduler.currentTime },
            wait = { waits += it; release.await() },
        ) {
            calls.incrementAndGet()
            SyncTurnResult(account, moreWork = true)
        }

        coordinator.requestManual()
        runCurrent()
        assertEquals(9, calls.get())
        assertEquals(listOf(100L), waits)
        coordinator.close()
    }

    @Test
    fun `local writes do not bypass transient retry cooldown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        listOf<AppError>(AppError.Network, AppError.Server(429), AppError.Server(500)).forEach { failure ->
            val calls = AtomicInteger()
            val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
                calls.incrementAndGet()
                throw AppException(failure)
            }

            coordinator.requestLocalWrite()
            runCurrent()
            coordinator.requestLocalWrite()
            coordinator.requestLocalWrite()
            runCurrent()

            assertEquals("$failure must retain transient cooldown", 1, calls.get())
            coordinator.close()
        }
    }

    @Test
    fun `manual request bypasses transient retry cooldown once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            calls.incrementAndGet()
            throw AppException(AppError.Network)
        }

        coordinator.requestLocalWrite()
        runCurrent()
        coordinator.requestManual()
        runCurrent()

        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun `a transient failure retries on cooldown without another signal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        var reachable = false
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            calls.incrementAndGet()
            if (!reachable) throw AppException(AppError.Network)
            SyncTurnResult(account)
        }

        coordinator.requestLocalWrite()
        runCurrent()
        assertEquals(1, calls.get())

        reachable = true
        advanceUntilIdle()
        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun `reconnect advances one retry after a transient failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        var reachable = false
        val coordinator = makeCoordinator(dispatcher, now = { testScheduler.currentTime }) {
            calls.incrementAndGet()
            if (!reachable) throw AppException(AppError.Network)
            SyncTurnResult(account)
        }

        coordinator.start()
        runCurrent()
        assertEquals(1, calls.get())

        reachable = true
        connectivity.reconnect()
        runCurrent()

        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun `a request during final turn completion is retained`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        lateinit var coordinator: SyncCoordinator
        coordinator = makeCoordinator(dispatcher) {
            if (calls.incrementAndGet() == 1) coordinator.requestLocalWrite()
            SyncTurnResult(account)
        }

        coordinator.requestManual()
        runCurrent()

        assertEquals(2, calls.get())
    }

    @Test
    fun `worker and foreground requests share one network lane`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = makeCoordinator(dispatcher) {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            }
            SyncTurnResult(account)
        }

        coordinator.requestManual()
        runCurrent()
        entered.await()
        val worker = backgroundScope.launch { coordinator.runWorkerTurn() }
        runCurrent()
        assertTrue("worker waits for the coordinator turn", !worker.isCompleted)

        release.complete(Unit)
        runCurrent()
        worker.join()
        assertEquals(2, calls.get())
    }

    private fun makeCoordinator(
        dispatcher: TestDispatcher,
        now: () -> Long = { 0L },
        wait: suspend (Long) -> Unit = { delay(it) },
        turn: suspend () -> SyncTurnResult,
    ): SyncCoordinator = SyncCoordinator(
        repository = repository,
        scheduler = scheduler,
        connectivity = connectivity,
        scope = CoroutineScope(dispatcher + SupervisorJob()).also { },
        nowMillis = now,
        waitMillis = wait,
        randomUnit = { 0.0 },
        runTurn = turn,
    ).also(coordinators::add)

    private class FakeConnectivity : SyncConnectivity {
        private var onAvailable: (() -> Unit)? = null
        override fun start(onAvailable: () -> Unit) { this.onAvailable = onAvailable }
        override fun stop() { onAvailable = null }
        fun reconnect() { onAvailable?.invoke() }
    }
}
