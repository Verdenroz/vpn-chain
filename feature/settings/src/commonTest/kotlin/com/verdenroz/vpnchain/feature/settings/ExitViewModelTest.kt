package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.DevicesFailed
import com.verdenroz.vpnchain.core.domain.ExitBoard as DomainExitBoard
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.relay.AgentExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

private const val PRIVATE_KEY = "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM="
private val CONF = """
    [Interface]
    PrivateKey = $PRIVATE_KEY
    Address = 10.2.0.2/32
    DNS = 10.2.0.1

    [Peer]
    PublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent()

@OptIn(ExperimentalCoroutinesApi::class)
class ExitViewModelTest {

    private val log = mutableListOf<String>()
    private val relay = FakeDevices(log)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ExitViewModel(relay)

    @Test
    fun `an admin on a direct exit sees the row`() {
        assertEquals(ExitBoard.Ready(AgentExit.Direct), viewModel().state.value.board)
    }

    @Test
    fun `an admin on a WireGuard exit sees its endpoint`() {
        relay.exitBoard = Result.success(DomainExitBoard(admin = true, exit = AgentExit.WireGuard("203.0.113.9:51820")))

        assertEquals(ExitBoard.Ready(AgentExit.WireGuard("203.0.113.9:51820")), viewModel().state.value.board)
    }

    @Test
    fun `a member and an unreachable relay see no row`() {
        relay.exitBoard = Result.success(DomainExitBoard(admin = false, exit = null))
        assertEquals(ExitBoard.Hidden, viewModel().state.value.board)

        relay.exitBoard = Result.failure(DevicesFailed(DevicesFailed.Reason.Offline))
        assertEquals(ExitBoard.Hidden, viewModel().state.value.board)
    }

    @Test
    fun `a pasted conf is reviewed by its endpoint before anything is sent`() {
        val model = viewModel()
        model.startChange()

        model.review(CONF)

        assertEquals(ExitStep.ConfirmWireGuard(CONF, "203.0.113.9:51820"), model.state.value.step)
        assertEquals(listOf("loadExit"), log)
    }

    @Test
    fun `a conf that cannot be an exit is refused in place and sent nowhere`() {
        val model = viewModel()
        model.startChange()

        model.review(CONF.replace("0.0.0.0/0", "10.0.0.0/8"))

        val step = assertIs<ExitStep.Pasting>(model.state.value.step)
        assertEquals(UiText.Dynamic("Only full-tunnel configs work. Set AllowedIPs to 0.0.0.0/0."), step.error)
        assertFalse(PRIVATE_KEY in model.state.value.toString())
        assertEquals(listOf("loadExit"), log)
    }

    @Test
    fun `confirming a WireGuard exit sends the conf, closes the step and reloads`() {
        val model = viewModel()
        model.review(CONF)

        model.confirm()

        assertNull(model.state.value.step)
        assertFalse(model.state.value.busy)
        assertEquals(listOf("loadExit", "setExit:WireGuard", "loadExit"), log)
    }

    @Test
    fun `going back to the VPS address asks first and then sends direct`() {
        relay.exitBoard = Result.success(DomainExitBoard(admin = true, exit = AgentExit.WireGuard("203.0.113.9:51820")))
        val model = viewModel()

        model.askDirect()
        assertEquals(ExitStep.ConfirmDirect, model.state.value.step)
        model.confirm()

        assertNull(model.state.value.step)
        assertEquals(listOf("loadExit", "setExit:Direct", "loadExit"), log)
    }

    @Test
    fun `a failed switch says nothing was changed and drops the conf`() {
        relay.setExitResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Failed))
        val model = viewModel()
        model.review(CONF)

        model.confirm()

        val state = model.state.value
        assertNull(state.step)
        assertIs<UiText.Resource>(state.error)
        assertFalse(PRIVATE_KEY in state.toString())
    }

    @Test
    fun `a refusal shows the relay's own words`() {
        relay.setExitResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Refused, "no endpoint in the WireGuard file"))
        val model = viewModel()
        model.review(CONF)

        model.confirm()

        assertEquals(UiText.Dynamic("no endpoint in the WireGuard file"), model.state.value.error)
    }

    @Test
    fun `cancelling a review drops the conf`() {
        val model = viewModel()
        model.review(CONF)

        model.dismiss()

        assertNull(model.state.value.step)
        assertFalse(PRIVATE_KEY in model.state.value.toString())
    }

    @Test
    fun `the row reloads when the tunnel or key changes`() {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        relay.changes = changes
        viewModel()

        changes.tryEmit(Unit)
        changes.tryEmit(Unit)

        assertEquals(listOf("loadExit", "loadExit"), log)
    }
}
