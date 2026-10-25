package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
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
    var written: List<String>? = null

    override fun path(kind: RuleSetKind) = "/learned"
    override fun write(kind: RuleSetKind, domains: List<String>) {
        if (kind == RuleSetKind.Warp) written = domains
    }
}

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

class DefaultLearnedSitesRepositoryTest {
    private var clock = 1_000L
    private val store = RecordingStore()
    private val repository = DefaultLearnedSitesRepository(
        preferences = VpnChainPreferencesDataSource(MemoryPreferences()),
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
    fun `learning a domain twice keeps one entry`() = runTest {
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Forbidden)
        repository.learn("example.com", LearnedRoute.Warp, BlockReason.Challenge)

        assertEquals(listOf(BlockReason.Challenge), repository.sites.first().map { it.reason })
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
