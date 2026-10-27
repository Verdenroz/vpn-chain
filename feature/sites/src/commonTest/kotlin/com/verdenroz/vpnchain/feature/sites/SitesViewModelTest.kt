package com.verdenroz.vpnchain.feature.sites

import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.data.WarpRepository
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class SitesViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `use my connection moves a blocked site to your connection and undo takes it back`() = runTest {
        val learned = FakeLearned(LearnedSite("reddit.com", BlockReason.Forbidden, 0L, LearnedRoute.DirectAvailable))
        val viewModel = SitesViewModel(learned, StubProfiles(), StubSettings(), StubWarp())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.act(viewModel.uiState.value.blocked.single())
        runCurrent()
        assertEquals(listOf(SiteRow("reddit.com", "reddit.com", SiteAction.Undo)), viewModel.uiState.value.direct)

        viewModel.act(viewModel.uiState.value.direct.single())
        runCurrent()

        assertEquals(listOf("allowDirect:reddit.com", "forget:reddit.com"), learned.calls)
        assertEquals(SiteAction.UseMyConnection, viewModel.uiState.value.blocked.single().action)
    }

    @Test
    fun `remove drops the site`() = runTest {
        val learned = FakeLearned(LearnedSite("reddit.com", BlockReason.Forbidden, 0L, LearnedRoute.Warp))
        val viewModel = SitesViewModel(learned, StubProfiles(), StubSettings(), StubWarp())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.act(viewModel.uiState.value.warp.single())
        runCurrent()

        assertEquals(listOf("remove:reddit.com"), learned.calls)
        assertEquals(SitesUiState(), viewModel.uiState.value)
    }
}

private val RELAY = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")

private class FakeLearned(vararg initial: LearnedSite) : LearnedSitesRepository {
    val calls = mutableListOf<String>()
    override val sites = MutableStateFlow(initial.toList())
    override suspend fun isSuppressed(domain: String) = false
    override suspend fun learn(domain: String, route: LearnedRoute, reason: BlockReason) = Unit
    override suspend fun remove(domain: String) {
        calls += "remove:$domain"
        sites.value = sites.value.filterNot { it.domain == domain }
    }
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

private class StubProfiles : ProfileRepository {
    override val profiles = MutableStateFlow(listOf(SavedProfile("r", "relay", relay = RELAY)))
    override val profile = MutableStateFlow<SavedProfile?>(profiles.value.first())
    override val activeProfileId = MutableStateFlow<String?>("r")
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = Unit
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = "x"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean) =
        Result.failure<WireGuardEntry>(IllegalStateException())
}

private class StubSettings : SettingsRepository {
    override val settings = MutableStateFlow(UserSettings())
    override val autostartSupported = false
    override suspend fun setAutoStartOnLogin(enabled: Boolean) = Result.success(Unit)
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) = Unit
    override suspend fun setSystemWideTun(enabled: Boolean) = Unit
    override suspend fun setKillSwitchEnabled(enabled: Boolean) = Unit
    override suspend fun setAllowLocalNetworks(enabled: Boolean) = Unit
    override suspend fun setDnsFilter(filter: DnsFilter) = Unit
    override suspend fun setEntryHopEnabled(enabled: Boolean) = Unit
    override suspend fun setWarpMode(mode: WarpMode) = Unit
    override suspend fun setLearnBlockedSites(enabled: Boolean) = Unit
    override suspend fun setFailOpen(enabled: Boolean) = Unit
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setConnectionLog(enabled: Boolean) = Unit
    override suspend fun setCloseToTray(enabled: Boolean) = Unit
}

private class StubWarp : WarpRepository {
    override val registered = flowOf(true)
    override suspend fun exit(): WarpExit? = null
}
