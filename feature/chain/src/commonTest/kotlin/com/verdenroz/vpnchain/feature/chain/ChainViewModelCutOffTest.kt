package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.domain.ReconnectPolicy
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChainViewModelCutOffTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `repeated silent attempts show the cut-off state`() = runTest {
        val rig = cutOffRig()

        assertTrue(rig.viewModel.uiState.value.cutOff)
        assertFalse(rig.viewModel.uiState.value.entryHop)
    }

    @Test
    fun `the cut-off state notes an active entry hop`() = runTest {
        val rig = cutOffRig(withEntryHop = true)

        assertTrue(rig.viewModel.uiState.value.cutOff)
        assertTrue(rig.viewModel.uiState.value.entryHop)
    }

    @Test
    fun `connect without entry hop turns it off and connects`() = runTest {
        val rig = cutOffRig(withEntryHop = true)

        rig.viewModel.connectWithoutEntryHop()
        runCurrent()

        assertFalse(rig.settings.settings.value.entryHopEnabled)
        assertEquals(1, rig.chain.connects)
        assertFalse(rig.viewModel.uiState.value.entryHop)
    }

    @Test
    fun `remove disconnects then deletes the active connection`() = runTest {
        val rig = cutOffRig()

        rig.viewModel.removeConnection()
        runCurrent()

        assertEquals(1, rig.chain.disconnects)
        assertEquals(listOf("r"), rig.profiles.deleted)
    }

    private fun kotlinx.coroutines.test.TestScope.cutOffRig(withEntryHop: Boolean = false): ChainRig {
        val rig = ChainRig(FakeLearned())
        if (withEntryHop) {
            val wireGuard = WireGuardEntry("k", "10.0.0.2/32", "peer", "203.0.113.20")
            rig.profiles.profile.value = SavedProfile("r", "relay", relay = chainRelay(), wireGuard = wireGuard)
        }
        rig.chain.connectionIntent.value = true
        rig.chain.status.value = rig.chain.status.value.copy(state = TunnelState.Connected)
        rig.chain.reconnectFails = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.supervisor.run() }
        runCurrent()
        rig.chain.status.value = rig.chain.status.value.copy(state = TunnelState.Error)
        advanceTimeBy(
            ReconnectPolicy.delayForAttemptMillis(1) + ReconnectPolicy.delayForAttemptMillis(2) +
                ReconnectPolicy.delayForAttemptMillis(3) + 100,
        )
        return rig
    }
}

private fun chainRelay() = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")
