package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.PairingFailed
import com.verdenroz.vpnchain.core.domain.PairingStep
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_expired
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_no_answer
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_reconnect_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_unreachable
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_used
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val LINK = "vpnchain://pair?v=1&t=SECRETTOKEN"
private val ALL_STEPS = listOf(PairingStep.Connecting, PairingStep.Registering, PairingStep.Reconnecting)

@OptIn(ExperimentalCoroutinesApi::class)
class PairingImportTest {

    private val log = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(pairing: FakePairing = FakePairing(log, ALL_STEPS)): SettingsViewModel {
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
            changeRouting = ChangeRoutingUseCase(settings, chain),
            relayPairing = pairing,
        )
    }

    private fun failed(viewModel: SettingsViewModel) = assertIs<PairingStatus.Failed>(viewModel.pairing.value).text

    @Test
    fun `a pasted link pairs instead of waiting for review`() {
        val viewModel = viewModel()

        viewModel.importSecretsEnv(LINK)

        assertEquals(PairingStatus.Done("203.0.113.10"), viewModel.pairing.value)
        assertNull(viewModel.pendingImport.value)
        assertEquals(listOf("pair"), log)
    }

    @Test
    fun `each step shows while it runs`() {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(FakePairing(log, listOf(PairingStep.Registering), gate = gate))

        viewModel.importSecretsEnv(LINK)

        assertEquals(PairingStatus.Working(PairingStep.Registering, "203.0.113.10"), viewModel.pairing.value)
        gate.complete(Unit)
        assertIs<PairingStatus.Done>(viewModel.pairing.value)
    }

    @Test
    fun `the paste field clears as soon as the link is taken`() {
        val viewModel = viewModel(FakePairing(log, result = Result.failure(PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Used))))

        viewModel.importSecretsEnv(LINK)

        assertEquals(1, viewModel.importsTaken.value)
    }

    @Test
    fun `a second link is ignored while one is pairing`() {
        val viewModel = viewModel(FakePairing(log, ALL_STEPS, gate = CompletableDeferred()))

        viewModel.importSecretsEnv(LINK)
        viewModel.importSecretsEnv(LINK)

        assertEquals(listOf("pair"), log)
    }

    @Test
    fun `each failure says what to do about it`() {
        val cases = listOf(
            PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Expired) to Res.string.settings_pairing_expired,
            PairingFailed(PairingStep.Registering, PairingFailed.Reason.Used) to Res.string.settings_pairing_used,
            PairingFailed(PairingStep.Connecting, PairingFailed.Reason.Unreachable) to Res.string.settings_pairing_unreachable,
            PairingFailed(PairingStep.Registering, PairingFailed.Reason.Unreachable) to Res.string.settings_pairing_no_answer,
            PairingFailed(PairingStep.Reconnecting, PairingFailed.Reason.Failed) to Res.string.settings_pairing_reconnect_failed,
        )

        for ((failure, expected) in cases) {
            val viewModel = viewModel(FakePairing(log, listOf(failure.step), Result.failure(failure)))
            viewModel.importSecretsEnv(LINK)
            assertEquals(UiText.Resource(expected, listOf("203.0.113.10")), failed(viewModel), failure.reason.name)
        }
    }

    @Test
    fun `a link the parser refuses shows its own wording`() {
        val viewModel = viewModel(FakePairing(log, result = Result.failure(IllegalArgumentException("This code is from a newer version. Update the app."))))

        viewModel.importSecretsEnv(LINK)

        assertEquals(UiText.Dynamic("This code is from a newer version. Update the app."), failed(viewModel))
    }

    @Test
    fun `dismissing clears the status`() {
        val viewModel = viewModel()
        viewModel.importSecretsEnv(LINK)

        viewModel.dismissPairing()

        assertNull(viewModel.pairing.value)
    }

    @Test
    fun `no status or message carries the link`() {
        val viewModel = viewModel(FakePairing(log, ALL_STEPS, Result.failure(PairingFailed(PairingStep.Registering, PairingFailed.Reason.Used))))

        viewModel.importSecretsEnv(LINK)

        assertFalse("SECRETTOKEN" in viewModel.pairing.value.toString())
        assertFalse("SECRETTOKEN" in viewModel.pendingImport.value.toString())
    }
}
