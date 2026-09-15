package com.vstokke.memos.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.data.SyncTurnResult
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/** A connectivity callback is only a wake hint; the sync request remains the reachability test. */
interface SyncConnectivity {
    fun start(onAvailable: () -> Unit)
    fun stop()
}

internal class UsableNetworkTracker<T> {
    private var usableNetwork: T? = null

    /** Returns true only when usable connectivity changes to a new network. */
    fun update(network: T, usable: Boolean): Boolean {
        if (!usable) {
            if (usableNetwork == network) usableNetwork = null
            return false
        }
        if (usableNetwork == network) return false
        usableNetwork = network
        return true
    }

    fun reset() {
        usableNetwork = null
    }
}

class AndroidSyncConnectivity(context: Context) : SyncConnectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = UsableNetworkTracker<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: (() -> Unit)? = null

    override fun start(onAvailable: () -> Unit) {
        if (callback != null) return
        listener = onAvailable
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                report(network, manager.getNetworkCapabilities(network))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                report(network, capabilities)
            }

            override fun onLost(network: Network) {
                tracker.update(network, usable = false)
            }
        }
        callback = networkCallback
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure {
                callback = null
                listener = null
            }
    }

    private fun report(network: Network, capabilities: NetworkCapabilities?) {
        val usable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (tracker.update(network, usable)) listener?.invoke()
    }

    override fun stop() {
        val registered = callback ?: return
        callback = null
        listener = null
        tracker.reset()
        runCatching { manager.unregisterNetworkCallback(registered) }
    }
}

object AlwaysConnected : SyncConnectivity {
    override fun start(onAvailable: () -> Unit) = Unit
    override fun stop() = Unit
}

enum class SyncWakeReason {
    STARTUP,
    LOCAL_WRITE,
    MANUAL,
    FOREGROUND,
    RECONNECT,
    WORKER,
}

/**
 * Owns the one application sync loop. Signals are conflated, while the generation counter makes a
 * signal arriving during the final part of a turn observable on the next turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinator(
    private val repository: MemoRepository,
    private val scheduler: SyncScheduler,
    private val connectivity: SyncConnectivity = AlwaysConnected,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    private val randomUnit: () -> Double = { Random.nextDouble() },
    private val runTurn: suspend () -> SyncTurnResult = { repository.syncTurn() },
) {
    private val runnerScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val requestedGeneration = AtomicLong(0)
    private val completedGeneration = AtomicLong(0)
    // Automatic continuation generations and externally requested generations have different
    // service semantics: external work may skip a continuation delay, but not transient backoff.
    private val externalGeneration = AtomicLong(0)
    private val servedExternalGeneration = AtomicLong(0)
    private val forcedAttempt = AtomicBoolean(false)
    private val turnMutex = Mutex()
    private var runner: Job? = null
    private var backoffStep = 0
    private var retryAfter = 0L

    /** Starts lifecycle observation and the initial durable-work check. Safe to call once/repeatedly. */
    fun start() {
        connectivity.start { request(SyncWakeReason.RECONNECT) }
        request(SyncWakeReason.STARTUP)
    }

    fun onForeground() = request(SyncWakeReason.FOREGROUND)
    fun requestManual() = request(SyncWakeReason.MANUAL)
    fun requestLocalWrite() = request(SyncWakeReason.LOCAL_WRITE)

    fun request(reason: SyncWakeReason = SyncWakeReason.LOCAL_WRITE) {
        requestedGeneration.incrementAndGet()
        if (reason != SyncWakeReason.WORKER) externalGeneration.incrementAndGet()
        if (reason == SyncWakeReason.MANUAL || reason == SyncWakeReason.RECONNECT ||
            reason == SyncWakeReason.FOREGROUND
        ) forcedAttempt.set(true)
        wake.trySend(Unit)
        ensureRunner()
        // This is a durable fallback, not the foreground request path. KEEP avoids putting a save
        // behind an old APPEND chain; the in-process runner still starts immediately.
        if (reason != SyncWakeReason.WORKER && repository.account() != null) {
            runCatching { scheduler.enqueueRepair() }
        }
    }

    private fun ensureRunner() {
        synchronized(this) {
            if (runner?.isActive == true) return
            runner = runnerScope.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        while (runnerScope.isActive) {
            awaitWake()
            var inlineTurns = 0
            var noProgressBursts = 0
            while (requestedGeneration.get() > completedGeneration.get() && runnerScope.isActive) {
                val force = forcedAttempt.getAndSet(false)
                if (!force && nowMillis() < retryAfter) {
                    val remaining = retryAfter - nowMillis()
                    select<Unit> {
                        onTimeout(remaining.coerceAtLeast(1L)) {}
                        wake.onReceive { }
                    }
                    continue
                }

                val generation = requestedGeneration.get()
                val externalAtTurnStart = externalGeneration.get()
                val outcome = runCatching { turnMutex.withLock { runTurn() } }
                completedGeneration.updateAndGet { maxOf(it, generation) }
                // Requests arriving after the turn-start snapshot remain unserved for the next
                // turn, including requests racing with the cap-reaching turn's completion.
                servedExternalGeneration.updateAndGet { maxOf(it, externalAtTurnStart) }
                val error = outcome.exceptionOrNull()
                if (error == null) {
                    backoffStep = 0
                    retryAfter = 0L
                    val result = outcome.getOrThrow()
                    if (result.moreWork) {
                        // Continue bounded turns in-process: a KEEP enqueue may be ignored while a
                        // worker is already running, and must not be the only follow-up mechanism.
                        if (result.pushProgress || result.pullProgress || result.pullCompleted) {
                            noProgressBursts = 0
                        }
                        if (inlineTurns < MAX_INLINE_TURNS) {
                            inlineTurns++
                            requestedGeneration.incrementAndGet()
                            yield()
                            continue
                        }
                        // Keep the generation alive after the burst cap. WorkManager is a durable
                        // safety net, not the authority for a healthy foreground process.
                        runCatching { scheduler.enqueueRepair() }
                        requestedGeneration.incrementAndGet()
                        // An external request that arrived during this turn is already pending;
                        // do not hide it behind an automatic continuation cooldown.
                        if (externalGeneration.get() > servedExternalGeneration.get()) continue
                        val wait = if (result.pushProgress || result.pullProgress || result.pullCompleted) {
                            INLINE_YIELD_MILLIS
                        } else {
                            val shift = noProgressBursts.coerceAtMost(MAX_NO_PROGRESS_STEP)
                            noProgressBursts++
                            min(MAX_NO_PROGRESS_DELAY, INLINE_YIELD_MILLIS shl shift)
                        }
                        waitInterruptibly(wait)
                        continue
                    }
                    // A signal received while the turn was completing is consumed immediately.
                    continue
                }
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (isTerminal(error)) {
                    backoffStep = 0
                    retryAfter = 0L
                } else {
                    backoffStep = (backoffStep + 1).coerceAtMost(MAX_BACKOFF_STEP)
                    val base = min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS shl (backoffStep - 1))
                    val jitter = (base * JITTER_FRACTION * randomUnit()).toLong()
                    retryAfter = nowMillis() + base + jitter
                    // Give the bounded retry a generation of its own. awaitWake() supplies the
                    // timer when no external callback or user action arrives.
                    requestedGeneration.incrementAndGet()
                    runCatching { scheduler.enqueueRepair() }
                }
                // Keep the single runner alive for a later manual/reconnect signal or cooldown
                // expiry. No recursive jobs are launched.
                break
            }
        }
    }

    /** Wait for the continuation timer or a new request, whichever arrives first. */
    private suspend fun waitInterruptibly(durationMillis: Long) {
        val externalAtWait = externalGeneration.get()
        if (externalAtWait > servedExternalGeneration.get()) return
        val timer = CompletableDeferred<Unit>()
        val timerJob = runnerScope.launch {
            waitMillis(durationMillis)
            timer.complete(Unit)
        }
        try {
            var interrupted = false
            while (!interrupted) {
                select<Unit> {
                    timer.onAwait { interrupted = true }
                    wake.onReceive {
                        // A wake left over from the request that started this run is not an
                        // interrupt. Only a newer external generation should bypass continuation
                        // delay; automatic continuation generations remain rate-limited.
                        if (externalGeneration.get() > externalAtWait) interrupted = true
                    }
                }
            }
        } finally {
            timerJob.cancel()
        }
    }

    private suspend fun awaitWake() {
        while (runnerScope.isActive) {
            val remaining = retryAfter - nowMillis()
            if (requestedGeneration.get() > completedGeneration.get() && remaining <= 0L) return
            if (remaining > 0L) {
                val signalled = select<Boolean> {
                    onTimeout(remaining) { false }
                    wake.onReceive { true }
                }
                if (signalled && requestedGeneration.get() > completedGeneration.get()) return
            } else {
                wake.receive()
            }
        }
    }

    private fun isTerminal(error: Throwable): Boolean = when (error) {
        is AppException -> error.error == AppError.Authentication ||
            error.error == AppError.SessionRefreshRejected ||
            error.error == AppError.Conflict || error.error == AppError.Permission ||
            error.error == AppError.InvalidCredentials || error.error == AppError.SignInFailed ||
            error.error == AppError.UnsupportedReminder || error.error == AppError.PendingAccount ||
            error.error is AppError.Server && error.error.status in 400..499 && error.error.status != 429
        else -> false
    }

    /** WorkManager enters the same network/mutation mutex rather than starting a second uploader. */
    suspend fun runWorkerTurn(): SyncTurnResult = turnMutex.withLock { runTurn() }

    fun close() {
        connectivity.stop()
        // Cancel the receive loop before closing its channel so shutdown cannot surface a
        // ClosedReceiveChannelException as an uncaught application-scope failure.
        runnerScope.coroutineContext[Job]?.cancel()
        wake.close()
    }

    private companion object {
        const val MAX_INLINE_TURNS = 8
        const val INLINE_YIELD_MILLIS = 100L
        const val MAX_NO_PROGRESS_STEP = 8
        const val MAX_NO_PROGRESS_DELAY = 15 * 60 * 1_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 15 * 60 * 1_000L
        const val MAX_BACKOFF_STEP = 8
        const val JITTER_FRACTION = 0.25
    }
}
