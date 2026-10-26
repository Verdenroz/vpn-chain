package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingRestartTest {

    private val log = mutableListOf<String>()
    private val settings = FakeSettings()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `turning failing open off moves a live tunnel without stopping it`() {
        viewModel(TunnelState.Connected).setFailOpen(false)

        assertEquals(listOf("restart"), log)
        assertEquals(false, settings.settings.value.failOpen)
    }

    @Test
    fun `turning the entry hop off moves a live tunnel without stopping it`() {
        viewModel(TunnelState.Connected).setEntryHopEnabled(false)

        assertEquals(listOf("restart"), log)
    }

    @Test
    fun `changing the WARP mode moves a live tunnel without stopping it`() {
        viewModel(TunnelState.Connected).setWarpMode(WarpMode.Off)

        assertEquals(listOf("restart"), log)
    }

    @Test
    fun `a stopped tunnel stays stopped`() {
        viewModel(TunnelState.Disconnected).setFailOpen(false)

        assertEquals(emptyList(), log)
        assertEquals(false, settings.settings.value.failOpen)
    }

    @Test
    fun `setting the value it already has restarts nothing`() {
        viewModel(TunnelState.Connected).setFailOpen(settings.settings.value.failOpen)

        assertEquals(emptyList(), log)
    }

    private fun viewModel(state: TunnelState): SettingsViewModel {
        val profiles = FakeProfiles(log)
        val chain = FakeChain(state, log)
        return SettingsViewModel(
            profileRepository = profiles,
            settingsRepository = settings,
            importProfile = ImportProfileUseCase(profiles),
            importWireGuardConf = ImportWireGuardConfUseCase(profiles, chain),
            switchProfile = SwitchProfileUseCase(profiles, chain),
            chainRepository = chain,
            learnedSites = FakeLearnedSites(log),
            changeRouting = ChangeRoutingUseCase(settings, chain),
        )
    }
}
