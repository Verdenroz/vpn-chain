package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.tunnel.ConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.HostMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class RecordingCloser(private val store: RecordingStore, private val scheduler: TestCoroutineScheduler) : ConnectionCloser {
    var failing = false
    var slowFirst = 0L
    var running = 0
    var mostAtOnce = 0
    val closed = mutableListOf<Pair<HostMatch, List<String>>>()
    val batches = mutableListOf<Pair<Long, List<HostMatch>>>()
    val tracked = mutableListOf<Boolean>()

    override suspend fun close(matches: Collection<HostMatch>) {
        if (failing) throw IllegalStateException("clash API unreachable")
        mostAtOnce = maxOf(mostAtOnce, ++running)
        batches += scheduler.currentTime to matches.toList()
        closed += matches.map { it to store.writtenDirect.orEmpty() }
        if (batches.size == 1) delay(slowFirst)
        running--
    }

    override fun track(wanted: Boolean) {
        tracked += wanted
    }
}

private class RecordingLogger : Logger {
    val warnings = mutableListOf<String>()
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) {
        warnings += message
    }
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

private val BANK = HostMatch("www.bank.example", subdomains = false)
private val REDDIT = HostMatch("reddit.com", subdomains = true)

@OptIn(ExperimentalCoroutinesApi::class)
class RerouteClosesTest {
    private val scope = TestScope()
    private val store = RecordingStore()
    private val preferences = LearnedSitesPreferences()
    private val closer = RecordingCloser(store, scope.testScheduler)
    private val logger = RecordingLogger()
    private val repository = DefaultLearnedSitesRepository(
        preferences = VpnChainPreferencesDataSource(preferences),
        store = store,
        closer = closer,
        now = { 1_000L },
        logger = logger,
        scope = scope,
        timeSource = scope.testScheduler.timeSource,
    )

    private suspend fun offerBank() = repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)

    @Test
    fun `sending a site direct closes its connections once sing-box has reloaded the rule-set`() = scope.runTest {
        offerBank()

        repository.allowDirect("www.bank.example")
        advanceTimeBy(299)

        assertTrue(closer.closed.isEmpty())

        advanceTimeBy(2)

        assertEquals(listOf(BANK to listOf("www.bank.example")), closer.closed)
    }

    @Test
    fun `undo closes the connections sent direct`() = scope.runTest {
        offerBank()
        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        repository.forget("www.bank.example")
        advanceUntilIdle()

        assertEquals(BANK to emptyList(), closer.closed.last())
    }

    @Test
    fun `a warp route at a registrable domain closes the names under it`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("github.io", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("login.bank.example", LearnedRoute.FailOpen, BlockReason.Forbidden)
        advanceUntilIdle()

        assertEquals(
            setOf(REDDIT, HostMatch("github.io", subdomains = false), HostMatch("login.bank.example", subdomains = false)),
            closer.closed.map { it.first }.toSet(),
        )
    }

    @Test
    fun `removing a warp site closes its connections`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        advanceUntilIdle()

        repository.remove("reddit.com")
        advanceUntilIdle()

        assertEquals(listOf(REDDIT, REDDIT), closer.closed.map { it.first })
    }

    @Test
    fun `a verdict that moves nothing closes nothing`() = scope.runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.RateLimited)
        repository.learn("b.com", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)
        repository.learn("c.com", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.remove("c.com")
        advanceUntilIdle()

        assertEquals(listOf(HostMatch("a.com", subdomains = true)), closer.closed.map { it.first })
    }

    @Test
    fun `verdicts that move nothing do not postpone a pending close`() = scope.runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        offerBank()
        advanceUntilIdle()
        val start = currentTime

        repository.allowDirect("www.bank.example")
        repeat(4) {
            advanceTimeBy(100)
            repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        }
        advanceUntilIdle()

        assertEquals(start + 300, closer.batches.last().first)
    }

    @Test
    fun `an unwritten move closes nothing until a write carries it`() = scope.runTest {
        offerBank()
        store.failing = true
        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        assertTrue(closer.closed.isEmpty())

        store.failing = false
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        advanceUntilIdle()

        assertEquals(setOf(BANK, REDDIT), closer.closed.map { it.first }.toSet())
        assertEquals(listOf("www.bank.example"), closer.closed.first().second)
    }

    @Test
    fun `a render for a connect drops the closes it carries`() = scope.runTest {
        offerBank()
        repository.allowDirect("www.bank.example")

        repository.syncRuleSets()
        advanceUntilIdle()

        assertTrue(closer.closed.isEmpty())
    }

    @Test
    fun `a disconnect drops pending closes`() = scope.runTest {
        offerBank()
        repository.allowDirect("www.bank.example")

        repository.forgetPendingCloses()
        advanceUntilIdle()

        assertTrue(closer.closed.isEmpty())
    }

    @Test
    fun `a late move waits for its own floor even past the cap`() = scope.runTest {
        offerBank()
        repository.learn("x.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        val start = currentTime
        closer.slowFirst = 2_850

        repository.allowDirect("x.example")
        advanceTimeBy(100)
        repository.allowDirect("www.bank.example")
        advanceTimeBy(2_870)
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        advanceUntilIdle()

        assertEquals(
            listOf(
                start + 300 to listOf(HostMatch("x.example", subdomains = false)),
                start + 3_150 to listOf(BANK),
                start + 3_270 to listOf(REDDIT),
            ),
            closer.batches,
        )
    }

    @Test
    fun `one close runs at a time`() = scope.runTest {
        offerBank()
        repository.learn("x.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        closer.slowFirst = 500

        repository.allowDirect("x.example")
        advanceTimeBy(400)
        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        assertEquals(1, closer.mostAtOnce)
        assertEquals(2, closer.batches.size)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `a close that fails leaves the route change in place`() = scope.runTest {
        offerBank()
        closer.failing = true

        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        assertEquals(LearnedRoute.Direct, repository.sites.first().single().route)
        assertEquals(listOf("www.bank.example"), store.writtenDirect)
        assertEquals(1, logger.warnings.size)
    }

    @Test
    fun `the engine keeps watching connections while a close is pending`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        advanceUntilIdle()

        repository.remove("reddit.com")
        runCurrent()

        assertEquals(true, closer.tracked.last())

        advanceUntilIdle()

        assertEquals(false, closer.tracked.last())
        assertEquals(REDDIT, closer.closed.last().first)
    }

    @Test
    fun `a removal while a probe's verdict is in flight stays removed`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        val gate = CompletableDeferred<Unit>()
        preferences.nextReadGate = gate

        launch { repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.RateLimited) }
        runCurrent()
        launch { repository.remove("reddit.com") }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.sites.first().isEmpty())
        assertTrue(repository.isSuppressed("reddit.com"))
    }
}
