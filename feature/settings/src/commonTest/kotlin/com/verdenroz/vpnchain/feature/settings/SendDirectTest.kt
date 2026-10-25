package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendDirectTest {

    private val log = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `send direct and undo reach the learned sites`() {
        val viewModel = viewModel()

        viewModel.sendDirect("reddit.com")
        viewModel.undoDirect("reddit.com")

        assertEquals(listOf("allowDirect:reddit.com", "forget:reddit.com"), log)
    }

    @Test
    fun `a relay with no entry hop offers the bare link`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        assertTrue(viewModel.uiState.value.directOffered)
    }

    private fun viewModel(): SettingsViewModel {
        val profiles = FakeProfiles(log)
        val chain = FakeChain(TunnelState.Disconnected, log)
        return SettingsViewModel(
            profileRepository = profiles,
            settingsRepository = FakeSettings(),
            importProfile = ImportProfileUseCase(profiles),
            importWireGuardConf = ImportWireGuardConfUseCase(profiles, chain),
            switchProfile = SwitchProfileUseCase(profiles, chain),
            chainRepository = chain,
            learnedSites = FakeLearnedSites(log),
        )
    }
}
