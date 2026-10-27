package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRules
import com.verdenroz.vpnchain.core.config.RuleSetKind
import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.ChainProfile
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val PROBE = ProbeProxy(relayPort = 1, warpPort = 2, fallbackPort = 3, username = "u", password = "p")
private val RELAY = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")
private val CHAIN = SavedProfile("c", "c", relay = RELAY, wireGuard = WireGuardEntry("k", "10.2.0.2/32", "p", "198.51.100.20"))
private val RELAY_ONLY = SavedProfile("r", "r", relay = RELAY)
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
    override fun path(kind: RuleSetKind) = "/learned"
    override fun write(kind: RuleSetKind, rules: LearnedRules) = Unit
}

private class LearnerPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class LearnerWorld(
    scope: CoroutineScope,
    preferences: VpnChainPreferencesDataSource = VpnChainPreferencesDataSource(LearnerPreferences()),
    script: (String, Int) -> ProbeResponse?,
) {
    val feed = SharedFeed()
    val fetcher = ScriptedFetcher(script)
    val gate = MutableStateFlow(true)
    val status = MutableStateFlow(ChainStatus(state = TunnelState.Connected))
    val settings = MutableStateFlow(UserSettings(warpMode = WarpMode.Automatic))
    val profile = MutableStateFlow<SavedProfile?>(CHAIN)
    val sessions = MutableStateFlow<LearningSession?>(LearningSession(PROBE, clashApi = null))
    val learned = DefaultLearnedSitesRepository(preferences, NoopStore(), NoopConnectionCloser(), now = { 1L }, logger = SilentLogger(), scope = scope)

    init {
        SiteLearner(
            feed = feed,
            prober = SiteProber(fetcher),
            learned = learned,
            settings = settings,
            profile = profile,
            gate = gate,
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
    fun `a site both exits refuse but the entry hop reaches is offered for it`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()

        assertEquals(LearnedRoute.EntryAvailable, world.learned.sites.first().single().route)
    }

    @Test
    fun `a double refusal is probed again once the session has an entry hop`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        world.settings.value = UserSettings(warpMode = WarpMode.Automatic, failOpen = false)
        world.sessions.value = LearningSession(PROBE.copy(fallbackPort = 0), clashApi = null)
        world.learned.learn("example.com", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()
        assertTrue(world.fetcher.calls.isEmpty())

        world.settings.value = UserSettings(warpMode = WarpMode.Automatic)
        world.sessions.value = LearningSession(PROBE, clashApi = null)
        runCurrent()
        world.feed.hosts.emit("example.com")
        runCurrent()

        assertEquals(LearnedRoute.EntryAvailable, world.learned.sites.first().single().route)
    }

    @Test
    fun `with no entry hop a double refusal is offered for the bare link and never probed there`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        world.profile.value = RELAY_ONLY
        world.sessions.value = LearningSession(PROBE.copy(fallbackPort = 0), clashApi = null)
        runCurrent()

        world.feed.hosts.emit("www.bank.example")
        runCurrent()

        val site = world.learned.sites.first().single()
        assertEquals("www.bank.example", site.domain)
        assertEquals(LearnedRoute.DirectAvailable, site.route)
        assertEquals(listOf(1, 2), world.fetcher.calls.map { it.second })
    }

    @Test
    fun `a running fallback probe is never used without an entry hop in the connection`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        world.profile.value = RELAY_ONLY
        runCurrent()

        world.feed.hosts.emit("www.bank.example")
        runCurrent()

        assertEquals(listOf(1, 2), world.fetcher.calls.map { it.second })
        assertEquals(LearnedRoute.DirectAvailable, world.learned.sites.first().single().route)
    }

    @Test
    fun `an adopted config whose fallback goes out direct never fails a site open`() = runTest {
        val legacy = """
            {"inbounds":[
              {"type":"mixed","tag":"probe-relay","listen":"127.0.0.1","listen_port":1,"users":[{"username":"u","password":"p"}]},
              {"type":"mixed","tag":"probe-warp","listen":"127.0.0.1","listen_port":2,"users":[{"username":"u","password":"p"}]},
              {"type":"mixed","tag":"probe-fallback","listen":"127.0.0.1","listen_port":3,"users":[{"username":"u","password":"p"}]}],
             "route":{"rules":[{"inbound":"probe-fallback","action":"route","outbound":"direct"}]}}
        """
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        world.sessions.value = learningSessionOf(legacy)
        runCurrent()

        world.feed.hosts.emit("www.bank.example")
        runCurrent()

        assertEquals(listOf(1, 2), world.fetcher.calls.map { it.second })
        assertTrue(world.learned.sites.first().none { it.route == LearnedRoute.FailOpen })
    }

    @Test
    fun `a host the user sent direct is never probed again`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        world.profile.value = RELAY_ONLY
        world.sessions.value = LearningSession(PROBE.copy(fallbackPort = 0), clashApi = null)
        world.learned.learn("shop.example", LearnedRoute.RefusedByBoth, BlockReason.Forbidden)
        world.learned.learn("www.shop.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        world.learned.allowDirect("www.shop.example")
        runCurrent()

        world.feed.hosts.emit("www.shop.example")
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
        assertEquals(LearnedRoute.Direct, world.learned.sites.first().single { it.domain == "www.shop.example" }.route)
    }

    @Test
    fun `a reachable host does not vouch for its siblings`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        runCurrent()

        world.feed.hosts.emit("www.example.com")
        world.feed.hosts.emit("api.example.com")
        runCurrent()

        assertEquals(listOf("www.example.com", "api.example.com"), world.fetcher.calls.map { it.first })
    }

    @Test
    fun `a host that is itself a public suffix is learned for WARP alone`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 1) REFUSED else OPEN }
        runCurrent()

        world.feed.hosts.emit("s3.amazonaws.com")
        runCurrent()

        assertEquals("s3.amazonaws.com", world.learned.sites.first().single().domain)
    }

    @Test
    fun `an entry hop that reaches a site is offered for the exact host`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        runCurrent()

        world.feed.hosts.emit("login.bank.example")
        runCurrent()

        val site = world.learned.sites.first().single()
        assertEquals("login.bank.example", site.domain)
        assertEquals(LearnedRoute.EntryAvailable, site.route)
    }

    @Test
    fun `a site offered for the bare link is probed again once an entry hop exists`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        world.learned.learn("www.bank.example", LearnedRoute.DirectAvailable, BlockReason.Forbidden)
        runCurrent()

        world.feed.hosts.emit("www.bank.example")
        runCurrent()

        assertEquals(LearnedRoute.EntryAvailable, world.learned.sites.first().single().route)
    }

    @Test
    fun `a site every probed exit refuses is offered the bare link, not learned for warp`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        runCurrent()

        world.feed.hosts.emit("api.example.com")
        runCurrent()

        val site = world.learned.sites.first().single()
        assertEquals("api.example.com", site.domain)
        assertEquals(LearnedRoute.DirectAvailable, site.route)
        assertEquals(listOf(1, 2, 3), world.fetcher.calls.map { it.second })
    }

    @Test
    fun `a site the entry hop reaches waits for the tap, then leaves by the entry hop only`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, port -> if (port == 3) OPEN else REFUSED }
        runCurrent()
        world.feed.hosts.emit("login.bank.example")
        runCurrent()

        assertEquals(LearnedRoute.EntryAvailable, world.learned.sites.first().single().route)
        world.learned.allowDirect("login.bank.example")

        assertEquals(LearnedRoute.FailOpen, world.learned.sites.first().single().route)
    }

    @Test
    fun `a site the entry hop refuses too waits for the tap, then leaves by the bare link`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        runCurrent()
        world.feed.hosts.emit("login.bank.example")
        runCurrent()

        assertEquals(LearnedRoute.DirectAvailable, world.learned.sites.first().single().route)
        assertEquals(listOf(1, 2, 3), world.fetcher.calls.map { it.second })
        world.learned.allowDirect("login.bank.example")

        assertEquals(LearnedRoute.Direct, world.learned.sites.first().single().route)
    }

    @Test
    fun `with warp off nothing is probed`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> REFUSED }
        world.settings.value = UserSettings(warpMode = WarpMode.Off)
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()

        assertTrue(world.fetcher.calls.isEmpty())
    }

    @Test
    fun `a reachable site is probed once and only through the relay`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        runCurrent()

        repeat(3) { world.feed.hosts.emit("a.example.com") }
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
    fun `sites removed by the user are not probed`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        world.learned.remove("removed.com")
        runCurrent()

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
    fun `names seen while the gate is shut are probed once it opens`() = runTest {
        val world = LearnerWorld(backgroundScope) { _, _ -> OPEN }
        world.gate.value = false
        runCurrent()

        world.feed.hosts.emit("example.com")
        runCurrent()
        assertTrue(world.fetcher.calls.isEmpty())

        world.gate.value = true
        runCurrent()

        assertEquals(listOf("example.com" to 1), world.fetcher.calls)
    }

    @Test
    fun `reachable verdicts survive a restart`() = runTest {
        val preferences = VpnChainPreferencesDataSource(LearnerPreferences())
        val first = LearnerWorld(backgroundScope, preferences) { _, _ -> OPEN }
        runCurrent()
        first.feed.hosts.emit("example.com")
        runCurrent()
        advanceTimeBy(5 * 60_000 + 1_000)
        runCurrent()

        val second = LearnerWorld(backgroundScope, preferences) { _, _ -> OPEN }
        runCurrent()
        second.feed.hosts.emit("example.com")
        runCurrent()

        assertEquals(1, first.fetcher.calls.size)
        assertTrue(second.fetcher.calls.isEmpty())
    }

    @Test
    fun `host names are normalised and addresses dropped`() {
        assertEquals("a.example.com", hostName("A.Example.com."))
        assertNull(hostName("example"))
        assertNull(hostName("192.168.1.1"))
        assertNull(hostName("printer.local"))
    }

    @Test
    fun `a name that is not a plain host name is never probed`() {
        assertNull(hostName("evil.com\r\nX-Injected: 1"))
        assertNull(hostName("evil.com:443"))
        assertNull(hostName("ex ample.com"))
        assertNull(hostName("[2001:db8::1]"))
    }
}
