package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RenameProfileTest {

    private val log = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a rename is saved trimmed`() {
        val viewModel = viewModel()

        viewModel.renameProfile("a", "  work  ")

        assertEquals(listOf("rename:a:work"), log)
        assertIs<SettingsMessage.Info>(viewModel.message.value)
    }

    @Test
    fun `a blank name is refused`() {
        val viewModel = viewModel()

        viewModel.renameProfile("a", "   ")

        assertEquals(emptyList(), log)
        assertIs<SettingsMessage.Error>(viewModel.message.value)
    }

    private fun viewModel(): SettingsViewModel {
        val profiles = FakeProfiles(log)
        val chain = FakeChain(TunnelState.Disconnected, log)
        val settings = FakeSettings()
        return SettingsViewModel(
            profileRepository = profiles,
            settingsRepository = settings,
            importProfile = ImportProfileUseCase(profiles),
            importWireGuardConf = ImportWireGuardConfUseCase(profiles, chain),
            switchProfile = SwitchProfileUseCase(profiles, chain),
            chainRepository = chain,
            learnedSites = FakeLearnedSites(),
            changeRouting = ChangeRoutingUseCase(settings, chain),
        )
    }
}
