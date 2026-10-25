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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private const val WEEK = 7L * 24 * 60 * 60 * 1000

private class RecordingStore : LearnedRuleSetStore {
    val files = mutableMapOf<RuleSetKind, LearnedRules>()
    val written get() = files[RuleSetKind.Warp]?.let { it.exact + it.suffixes }
    val writtenOpen get() = files[RuleSetKind.Open]?.let { it.exact + it.suffixes }
    val writtenDirect get() = files[RuleSetKind.Direct]?.let { it.exact + it.suffixes }

    override fun path(kind: RuleSetKind) = "/learned"
    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        files[kind] = rules
    }
}

private class LearnedSitesPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

class DefaultLearnedSitesRepositoryTest {
    private var clock = 1_000L
    private val store = RecordingStore()
    private val repository = DefaultLearnedSitesRepository(
        preferences = VpnChainPreferencesDataSource(LearnedSitesPreferences()),
        store = store,
        now = { clock },
    )

    @Test
    fun `a learned site is stored with its reason and time and written to the rule-set`() = runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        val site = repository.sites.first().single()
        assertEquals("example.com", site.domain)
        assertEquals(BlockReason.Forbidden, site.reason)
        assertEquals(1_000L, site.learnedAtMillis)
        assertEquals(listOf("example.com"), store.written)
    }

    @Test
    fun `sites that stay on the relay are kept out of the warp rule-set`() = runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)

        assertEquals(listOf("a.com"), store.written)
    }

    @Test
    fun `sites that fail open go in their own rule-set`() = runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.FailOpen, BlockReason.Forbidden)

        assertEquals(listOf("a.com"), store.written)
        assertEquals(listOf("b.com"), store.writtenOpen)
    }

    @Test
    fun `a site offered for the bare link is in no rule-set`() = runTest {
        repository.learn("bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)

        assertEquals(null, store.writtenOpen)
        assertEquals(null, store.writtenDirect)
    }

    @Test
    fun `allowing a site sends it direct and forgetting takes it back`() = runTest {
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
    fun `only a site offered for the bare link can be sent there`() = runTest {
        repository.learn("a.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("b.com", LearnedRoute.RefusedEverywhere, BlockReason.Forbidden)

        repository.allowDirect("a.com")
        repository.allowDirect("b.com")
        repository.allowDirect("unknown.com")

        assertEquals(listOf(LearnedRoute.Warp, LearnedRoute.RefusedEverywhere), repository.sites.first().map { it.route })
        assertEquals(emptyList(), store.writtenDirect.orEmpty())
    }

    @Test
    fun `sites failing open through the entry hop never reach the bare link's rule-set`() = runTest {
        repository.learn("b.com", LearnedRoute.FailOpen, BlockReason.Forbidden)

        assertEquals(listOf("b.com"), store.writtenOpen)
        assertEquals(emptyList(), store.writtenDirect)
    }

    @Test
    fun `only warp entries at a registrable domain match the names under them`() = runTest {
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
    fun `a probe never downgrades a site the user sent direct`() = runTest {
        repository.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.allowDirect("www.shop.example")

        repository.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        repository.learn("www.shop.example", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)

        assertEquals(LearnedRoute.Direct, repository.sites.first().single().route)
    }

    @Test
    fun `learning a domain twice keeps one entry`() = runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.RateLimited)

        assertEquals(listOf(BlockReason.RateLimited), repository.sites.first().map { it.reason })
    }

    @Test
    fun `a removed site leaves the rule-set and is not relearned for a week`() = runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        repository.remove("example.com")
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertTrue(repository.sites.first().isEmpty())
        assertEquals(emptyList(), store.written)
        assertTrue(repository.isSuppressed("example.com"))
    }

    @Test
    fun `suppression lapses after seven days`() = runTest {
        repository.remove("example.com")

        clock += WEEK + 1
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)

        assertEquals(listOf("example.com"), repository.sites.first().map { it.domain })
    }
}
