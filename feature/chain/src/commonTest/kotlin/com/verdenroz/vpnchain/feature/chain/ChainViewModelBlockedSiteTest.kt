package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ConnectivityRepository
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.OriginRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.ChainSupervisor
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.geoip.HopLocation
import com.verdenroz.vpnchain.core.geoip.NetworkProbe
import com.verdenroz.vpnchain.core.geoip.PublicIpSample
import com.verdenroz.vpnchain.core.geoip.RouteGeolocator
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ChainViewModelBlockedSiteTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a site that can go over the user's connection is prompted`() = runTest {
        val viewModel = chainViewModel(FakeLearned(offered("reddit.com", 1L)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(BlockedSite("reddit.com", "reddit.com"), viewModel.uiState.value.blockedSite)
    }

    @Test
    fun `not now hides the prompt and leaves the site waiting`() = runTest {
        val learned = FakeLearned(offered("reddit.com", 1L))
        val viewModel = chainViewModel(learned)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.dismissBlocked("reddit.com")
        runCurrent()

        assertNull(viewModel.uiState.value.blockedSite)
        assertEquals(emptyList(), learned.calls)
        assertEquals(LearnedRoute.DirectAvailable, learned.sites.value.single().route)
    }

    @Test
    fun `use my connection allows the site and clears the prompt`() = runTest {
        val learned = FakeLearned(offered("reddit.com", 1L))
        val viewModel = chainViewModel(learned)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.sendDirect("reddit.com")
        runCurrent()

        assertEquals(listOf("allowDirect:reddit.com"), learned.calls)
        assertNull(viewModel.uiState.value.blockedSite)
    }

    @Test
    fun `one prompt at a time, newest first`() = runTest {
        val learned = FakeLearned(offered("old.example", 1L), offered("new.example", 2L))
        val viewModel = chainViewModel(learned)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals("new.example", viewModel.uiState.value.blockedSite?.domain)

        viewModel.dismissBlocked("new.example")
        runCurrent()

        assertEquals("old.example", viewModel.uiState.value.blockedSite?.domain)
    }
}

private fun offered(domain: String, learnedAtMillis: Long) =
    LearnedSite(domain, BlockReason.Forbidden, learnedAtMillis, LearnedRoute.DirectAvailable)

private val RELAY = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")

private fun chainViewModel(learned: LearnedSitesRepository): ChainViewModel = ChainRig(learned).viewModel

internal class ChainRig(
    learned: LearnedSitesRepository,
    val chain: StubChain = StubChain(),
    val profiles: StubProfiles = StubProfiles(),
    val settings: StubSettings = StubSettings(),
) {
    val supervisor = ChainSupervisor(chain, profiles, settings, StubConnectivity(), { 0L })

    val viewModel = ChainViewModel(
        connectChain = ConnectChainUseCase(chain),
        disconnectChain = DisconnectChainUseCase(chain),
        observeChainRoute = ObserveChainRouteUseCase(profiles, settings, chain, StubOrigin(), StubGeolocator(), StubProbe()),
        chainRepository = chain,
        profileRepository = profiles,
        settingsRepository = settings,
        learnedSites = learned,
        supervisor = supervisor,
    )
}

internal class FakeLearned(vararg initial: LearnedSite) : LearnedSitesRepository {
    val calls = mutableListOf<String>()
    override val sites = MutableStateFlow(initial.toList())
    override suspend fun isSuppressed(domain: String) = false
    override suspend fun learn(domain: String, route: LearnedRoute, reason: BlockReason) = Unit
    override suspend fun remove(domain: String) = Unit
    override suspend fun allowDirect(domain: String) {
        calls += "allowDirect:$domain"
        reroute(domain, LearnedRoute.Direct)
    }
    override suspend fun forget(domain: String) {
        calls += "forget:$domain"
        reroute(domain, LearnedRoute.DirectAvailable)
    }
    override suspend fun reachable(): Map<String, Long> = emptyMap()
    override suspend fun saveReachable(domains: Map<String, Long>) = Unit
    override suspend fun syncRuleSets() = Unit
    override suspend fun forgetPendingCloses() = Unit

    private fun reroute(domain: String, route: LearnedRoute) {
        sites.value = sites.value.map { if (it.domain == domain) it.copy(route = route) else it }
    }
}

internal class StubChain : ChainRepository {
    override val status = MutableStateFlow(ChainStatus())
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    var reconnectFails = false
    var disconnects = 0
    var connects = 0
    override suspend fun connect(): Result<Unit> {
        connects++
        return Result.success(Unit)
    }
    override suspend fun reconnect(): Result<Unit> {
        if (!reconnectFails) return Result.success(Unit)
        status.value = status.value.copy(carriedNothing = true)
        return Result.failure(IllegalStateException("silent"))
    }
    override suspend fun disconnect() {
        disconnects++
    }
    override suspend fun release() = Unit
}

internal class StubProfiles : ProfileRepository {
    override val profiles = MutableStateFlow(listOf(SavedProfile("r", "relay", relay = RELAY)))
    override val profile = MutableStateFlow<SavedProfile?>(profiles.value.first())
    override val activeProfileId = MutableStateFlow<String?>("r")
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = Unit
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = "x"
    override suspend fun rename(id: String, name: String) = Unit
    val deleted = mutableListOf<String>()
    override suspend fun delete(id: String) {
        deleted += id
    }
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean) =
        Result.failure<WireGuardEntry>(IllegalStateException())
}

internal class StubSettings : SettingsRepository {
    override val settings = MutableStateFlow(UserSettings(warpMode = WarpMode.Automatic))
    override val autostartSupported = false
    override suspend fun setAutoStartOnLogin(enabled: Boolean) = Result.success(Unit)
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) = Unit
    override suspend fun setSystemWideTun(enabled: Boolean) = Unit
    override suspend fun setKillSwitchEnabled(enabled: Boolean) = Unit
    override suspend fun setAllowLocalNetworks(enabled: Boolean) = Unit
    override suspend fun setDnsFilter(filter: DnsFilter) = Unit
    override suspend fun setEntryHopEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(entryHopEnabled = enabled)
    }
    override suspend fun setWarpMode(mode: WarpMode) = Unit
    override suspend fun setLearnBlockedSites(enabled: Boolean) = Unit
    override suspend fun setFailOpen(enabled: Boolean) = Unit
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit

    override suspend fun setConnectionLog(enabled: Boolean) = Unit
    override suspend fun setCloseToTray(enabled: Boolean) = Unit
}

internal class StubOrigin : OriginRepository {
    override val lastKnownOriginIp = MutableStateFlow<String?>(null)
    override suspend fun record(ip: String) = Unit
}

internal class StubGeolocator : RouteGeolocator {
    override suspend fun locate(hostOrIp: String): HopLocation? = null
}

internal class StubProbe : NetworkProbe {
    override suspend fun samplePublicIp(): PublicIpSample? = null
}

internal class StubConnectivity : ConnectivityRepository {
    override val online = MutableStateFlow(true)
    override val linkChanges = MutableSharedFlow<Unit>()
}
