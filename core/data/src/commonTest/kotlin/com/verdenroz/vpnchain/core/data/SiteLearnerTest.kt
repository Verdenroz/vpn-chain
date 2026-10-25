package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.tunnel.DestinationFeed
import com.verdenroz.vpnchain.core.warp.probe.ProbeResponse
import com.verdenroz.vpnchain.core.warp.probe.SiteFetcher
import com.verdenroz.vpnchain.core.warp.probe.SiteProber
import com.verdenroz.vpnchain.core.config.ClashApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val PROBE = ProbeProxy(relayPort = 1, warpPort = 2, username = "u", password = "p")
private val OPEN = ProbeResponse(200, emptyMap(), "ok", complete = true)
private val REFUSED = ProbeResponse(403, emptyMap(), "", complete = true)

private class ScriptedFetcher(private val script: (host: String, port: Int) -> ProbeResponse?) : SiteFetcher {
    val calls = mutableListOf<Pair<String, Int>>()

    override suspend fun get(host: String, port: Int, proxy: ProbeProxy): ProbeResponse? {
        calls += host to port
        return script(host, port)
    }
}

private class SharedFeed : DestinationFeed {
    val hosts = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override fun hosts(clashApi: ClashApi?): Flow<String> = hosts
}

private class NoopStore : LearnedRuleSetStore {
    override fun path(route: LearnedRoute) = "/learned"
    override fun write(route: LearnedRoute, domains: List<String>) = Unit
}

private class LearnerPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class LearnerWorld(scope: CoroutineScope, script: (String, Int) -> ProbeResponse?) {
    val feed = SharedFeed()
    val fetcher = ScriptedFetcher(script)
    val status = MutableStateFlow(ChainStatus(state = TunnelState.Connected))
    val settings = MutableStateFlow(UserSettings(warpMode = WarpMode.BlockedSites))
    val sessions = LearningSessionStore().apply { current.value = LearningSession(PROBE, clashApi = null) }
    val learned = DefaultLearnedSitesRepository(VpnChainPreferencesDataSource(LearnerPreferences()), NoopStore(), now = { 1L })

    init {
        SiteLearner(
            feed = feed,
            prober = SiteProber(fetcher),
            learned = learned,
            settings = settings,
            sessions = sessions,
            status = status,
            now = { 1L },
            scope = scope,
        )
    }
}

class SiteLearnerTest {

    @Test
    fun `a site the relay refuses and warp answers is learned for warp`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 1) REFUSED else OPEN }
        runCurrent()

        world.feed.hosts.emit("www.example.com")
        runCurrent()

        val site = world.learned.sites.first().single()
        assertEquals("example.com", site.domain)
        assertEquals(LearnedRoute.Warp, site.route)
        assertEquals(BlockReason.Forbidden, site.reason)
    }

    @Test
    fun `a site both exits refuse is recorded as such`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()

        assertEquals(LearnedRoute.RefusedByBoth, world.learned.sites.first().single().route)
    }

    @Test
    fun `a reachable site is probed once and only through the relay`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        runCurrent()

        repeat(3) { world.feed.hosts.emit("a.example.com") }
        world.feed.hosts.emit("b.example.com")
        runCurrent()

        assertEquals(listOf("a.example.com" to 1), world.fetcher.calls)
        assertTrue(world.learned.sites.first().isEmpty())
    }

    @Test
    fun `nothing is probed while the chain is down`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        world.status.value = ChainStatus(state = TunnelState.Disconnected)
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
    }

    @Test
    fun `nothing is probed when learning is switched off`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        world.settings.value = world.settings.value.copy(learnBlockedSites = false)
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
    }

    @Test
    fun `sites on the manual list or removed by the user are not probed`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        world.settings.value = world.settings.value.copy(warpDomains = listOf("manual.com"))
        world.learned.remove("removed.com")
        runCurrent()

        world.feed.hosts.emit("www.manual.com")
        world.feed.hosts.emit("removed.com")
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
    }

    @Test
    fun `addresses and local names are never probed`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        runCurrent()

        listOf("10.0.0.1", "printer.local", "localhost", "[::1]").forEach { world.feed.hosts.emit(it) }
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
    }

    @Test
    fun `registrable domains keep a country second level`() {
        assertEquals("example.com", registrableDomain("a.b.example.com"))
        assertEquals("example.co.uk", registrableDomain("www.example.co.uk"))
        assertEquals("example.de", registrableDomain("www.example.de"))
        assertNull(registrableDomain("example"))
        assertNull(registrableDomain("192.168.1.1"))
    }
}
