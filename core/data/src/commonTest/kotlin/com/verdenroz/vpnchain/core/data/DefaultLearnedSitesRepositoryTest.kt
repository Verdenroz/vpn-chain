package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRules
import com.verdenroz.vpnchain.core.config.RuleSetKind
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.tunnel.ConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.HostMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private const val WEEK = 7L * 24 * 60 * 60 * 1000

private class RecordingStore : LearnedRuleSetStore {
    var failing = false
    val files = mutableMapOf<RuleSetKind, LearnedRules>()
    val written get() = files[RuleSetKind.Warp]?.let { it.exact + it.suffixes }
    val writtenOpen get() = files[RuleSetKind.Open]?.let { it.exact + it.suffixes }
    val writtenDirect get() = files[RuleSetKind.Direct]?.let { it.exact + it.suffixes }

    override fun path(kind: RuleSetKind) = "/learned"
    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        if (failing) throw IllegalStateException("disk full")
        files[kind] = rules
    }
}

private class LearnedSitesPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** Records each close with the direct rule-set as it stood when the close ran. */
private class RecordingCloser(private val store: RecordingStore) : ConnectionCloser {
    var failing = false
    val closed = mutableListOf<Pair<HostMatch, List<String>>>()

    override suspend fun close(match: HostMatch) {
        if (failing) throw IllegalStateException("clash API unreachable")
        closed += match to store.writtenDirect.orEmpty()
    }
}

class DefaultLearnedSitesRepositoryTest {
    private var clock = 1_000L
    private val scope = TestScope()
    private val store = RecordingStore()
    private val closer = RecordingCloser(store)
    private val repository = DefaultLearnedSitesRepository(
        preferences = VpnChainPreferencesDataSource(LearnedSitesPreferences()),
        store = store,
        closer = closer,
        now = { clock },
        logger = SilentLogger(),
        scope = scope,
    )

    @Test
    fun `sending a site direct closes its connections once sing-box has reloaded the rule-set`() = scope.runTest {
        repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)

        repository.allowDirect("www.bank.example")
        advanceTimeBy(999)

        assertTrue(closer.closed.isEmpty())

        advanceTimeBy(2)

        assertEquals(listOf(HostMatch("www.bank.example", subdomains = false) to listOf("www.bank.example")), closer.closed)
    }

    @Test
    fun `undo closes the connections sent direct`() = scope.runTest {
        repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        repository.forget("www.bank.example")
        advanceUntilIdle()

        assertEquals(HostMatch("www.bank.example", subdomains = false) to emptyList(), closer.closed.last())
    }

    @Test
    fun `a warp route at a registrable domain closes the names under it`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("github.io", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("login.bank.example", LearnedRoute.FailOpen, BlockReason.Forbidden)
        advanceUntilIdle()

        assertEquals(
            listOf(
                HostMatch("reddit.com", subdomains = true),
                HostMatch("github.io", subdomains = false),
                HostMatch("login.bank.example", subdomains = false),
            ),
            closer.closed.map { it.first },
        )
    }

    @Test
    fun `removing a warp site closes its connections`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        advanceUntilIdle()

        repository.remove("reddit.com")
        advanceUntilIdle()

        assertEquals(List(2) { HostMatch("reddit.com", subdomains = true) }, closer.closed.map { it.first })
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
    fun `an unwritten rule-set closes nothing`() = scope.runTest {
        repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        store.failing = true

        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        assertTrue(closer.closed.isEmpty())
    }

    @Test
    fun `a close that fails leaves the route change in place`() = scope.runTest {
        repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        closer.failing = true

        repository.allowDirect("www.bank.example")
        advanceUntilIdle()

        assertEquals(LearnedRoute.Direct, repository.sites.first().single().route)
        assertEquals(listOf("www.bank.example"), store.writtenDirect)

        closer.failing = false
        repository.forget("www.bank.example")
        advanceUntilIdle()

        assertEquals(listOf(HostMatch("www.bank.example", subdomains = false) to emptyList()), closer.closed)
    }

    @Test
    fun `a learned site is stored with its reason and time and written to the rule-set`() = scope.runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        val site = repository.sites.first().single()
        assertEquals("example.com", site.domain)
        assertEquals(BlockReason.Forbidden, site.reason)
        assertEquals(1_000L, site.learnedAtMillis)
        assertEquals(listOf("example.com"), store.written)
    }

    @Test
    fun `sites that stay on the relay are kept out of the warp rule-set`() = scope.runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)

        assertEquals(listOf("a.com"), store.written)
    }

    @Test
    fun `sites that fail open go in their own rule-set`() = scope.runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.FailOpen, BlockReason.Forbidden)

        assertEquals(listOf("a.com"), store.written)
        assertEquals(listOf("b.com"), store.writtenOpen)
    }

    @Test
    fun `a site offered for the bare link is in no rule-set`() = scope.runTest {
        repository.learn("bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)

        assertEquals(null, store.writtenOpen)
        assertEquals(null, store.writtenDirect)
    }

    @Test
    fun `allowing a site sends it direct and forgetting takes it back`() = scope.runTest {
        repository.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)

        repository.allowDirect("www.bank.example")

        assertEquals(LearnedRoute.Direct, repository.sites.first().single().route)
        assertEquals(listOf("www.bank.example"), store.writtenDirect)
        assertEquals(listOf("www.bank.example"), store.writtenOpen)

        repository.forget("www.bank.example")

        assertEquals(LearnedRoute.DirectAvailable, repository.sites.first().single().route)
        assertEquals(emptyList(), store.writtenDirect)
        assertEquals(emptyList(), store.writtenOpen)
    }

    @Test
    fun `only a site offered for the bare link can be sent there`() = scope.runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.RefusedEverywhere, BlockReason.Forbidden)

        repository.allowDirect("a.com")
        repository.allowDirect("b.com")
        repository.allowDirect("unknown.com")

        assertEquals(listOf(LearnedRoute.Warp, LearnedRoute.RefusedEverywhere), repository.sites.first().map { it.route })
        assertEquals(emptyList(), store.writtenDirect.orEmpty())
    }

    @Test
    fun `sites failing open through the entry hop never reach the bare link's rule-set`() = scope.runTest {
        repository.learn("b.com", LearnedRoute.FailOpen, BlockReason.Forbidden)

        assertEquals(listOf("b.com"), store.writtenOpen)
        assertEquals(emptyList(), store.writtenDirect)
    }

    @Test
    fun `only warp entries at a registrable domain match the names under them`() = scope.runTest {
        repository.learn("reddit.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("github.io", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("s3.amazonaws.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("login.bank.example", LearnedRoute.FailOpen, BlockReason.Forbidden)
        repository.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.allowDirect("www.shop.example")

        assertEquals(LearnedRules(exact = listOf("github.io", "s3.amazonaws.com"), suffixes = listOf("reddit.com")), store.files[RuleSetKind.Warp])
        assertEquals(LearnedRules(exact = listOf("login.bank.example", "www.shop.example")), store.files[RuleSetKind.Open])
        assertEquals(LearnedRules(exact = listOf("www.shop.example")), store.files[RuleSetKind.Direct])
    }

    @Test
    fun `a probe never downgrades a site the user sent direct`() = scope.runTest {
        repository.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.allowDirect("www.shop.example")

        repository.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.learn("www.shop.example", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)

        assertEquals(LearnedRoute.Direct, repository.sites.first().single().route)
    }

    @Test
    fun `two sites learned at once both reach the rule-set`() = scope.runTest {
        coroutineScope {
            launch { repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden) }
            launch { repository.learn("b.com", LearnedRoute.Warp, BlockReason.Forbidden) }
        }

        assertEquals(setOf("a.com", "b.com"), store.written.orEmpty().toSet())
    }

    @Test
    fun `a rule-set that cannot be written keeps the site and is written on the next change`() = scope.runTest {
        store.failing = true
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertEquals(listOf("a.com"), repository.sites.first().map { it.domain })

        store.failing = false
        repository.learn("b.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertEquals(setOf("a.com", "b.com"), store.written.orEmpty().toSet())
    }

    @Test
    fun `learning a domain twice keeps one entry`() = scope.runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.RateLimited)

        assertEquals(listOf(BlockReason.RateLimited), repository.sites.first().map { it.reason })
    }

    @Test
    fun `a removed site leaves the rule-set and is not relearned for a week`() = scope.runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        repository.remove("example.com")
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertTrue(repository.sites.first().isEmpty())
        assertEquals(emptyList(), store.written)
        assertTrue(repository.isSuppressed("example.com"))
    }

    @Test
    fun `suppression lapses after seven days`() = scope.runTest {
        repository.remove("example.com")

        clock += WEEK + 1
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertEquals(listOf("example.com"), repository.sites.first().map { it.domain })
    }
}
