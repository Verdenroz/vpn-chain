package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.DeviceBoard
import com.verdenroz.vpnchain.core.domain.DevicesFailed
import com.verdenroz.vpnchain.core.domain.ExitBoard
import com.verdenroz.vpnchain.core.domain.RelayDevices
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.relay.AgentDevice
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.relay.ExitChange
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.PairingCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LAPTOP = AgentDevice("laptop", DeviceRole.Admin, added = "2026-10-01T00:00:00Z", up = 1_000, down = 2_000, seen = "2026-10-26T07:33:20Z")
private val PHONE = AgentDevice("phone", DeviceRole.Member, added = "2026-10-02T00:00:00Z", up = 0, down = 0)
private val CODE = PairingCode("vpnchain://pair?v=1&t=SECRETTOKEN", expires = 2_000)

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val log = mutableListOf<String>()
    private val relay = FakeDevices(log)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DevicesViewModel(relay)

    private fun DevicesViewModel.ready() = assertIs<DevicesBoard.Ready>(state.value.board)

    @Test
    fun `an admin sees every device with this one marked`() {
        val ready = viewModel().ready()

        assertTrue(ready.admin)
        assertEquals(listOf("laptop", "phone"), ready.devices.map { it.name })
        assertEquals(listOf(true, false), ready.devices.map { it.self })
        assertEquals(3_000L, ready.devices.first().bytes)
        assertNull(ready.devices.last().seen)
    }

    @Test
    fun `a member sees its own device and is no admin`() {
        relay.board = Result.success(DeviceBoard(listOf(PHONE), self = "phone", admin = false, poolEmpty = false))

        val ready = viewModel().ready()

        assertFalse(ready.admin)
        assertEquals(listOf("phone"), ready.devices.map { it.name })
    }

    @Test
    fun `no management key and no tunnel each read as one plain reason`() {
        relay.board = Result.failure(DevicesFailed(DevicesFailed.Reason.NoKey))
        assertEquals(DevicesBoard.Unavailable(DevicesBoard.Why.NoKey), viewModel().state.value.board)

        relay.board = Result.failure(DevicesFailed(DevicesFailed.Reason.Offline))
        assertEquals(DevicesBoard.Unavailable(DevicesBoard.Why.Offline), viewModel().state.value.board)

        relay.board = Result.failure(DevicesFailed(DevicesFailed.Reason.Failed))
        assertEquals(DevicesBoard.Unavailable(DevicesBoard.Why.Failed), viewModel().state.value.board)
    }

    @Test
    fun `adding a device shows its code and a refresh lists it`() {
        val model = viewModel()

        model.startAdd()
        model.add("tablet")

        val step = assertIs<DevicesStep.ShowCode>(model.state.value.step)
        assertEquals(CODE, step.code)
        assertEquals("tablet", step.name)
        assertEquals(listOf("load", "add:tablet", "load"), log)
    }

    @Test
    fun `closing the code drops it`() {
        val model = viewModel()
        model.add("tablet")

        model.dismiss()

        assertNull(model.state.value.step)
        assertFalse("SECRETTOKEN" in model.state.value.toString())
    }

    @Test
    fun `a name relayd would refuse is not sent`() {
        val model = viewModel()
        model.startAdd()

        model.add("Not A Name")

        assertNotNull(assertIs<DevicesStep.Naming>(model.state.value.step).error)
        assertEquals(listOf("load"), log)
    }

    @Test
    fun `a name that is taken keeps the form open with a reason`() {
        relay.addResult = Result.failure(DevicesFailed(DevicesFailed.Reason.NameTaken))
        val model = viewModel()
        model.startAdd()

        model.add("phone")

        assertIs<UiText.Resource>(assertIs<DevicesStep.Naming>(model.state.value.step).error)
    }

    @Test
    fun `an empty pool asks before restarting, then restarts and adds`() {
        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = true))
        val model = viewModel()

        model.add("tablet")

        assertEquals(DevicesStep.ConfirmRestart("tablet"), model.state.value.step)
        assertEquals(listOf("load"), log)

        model.restartAndAdd()

        assertIs<DevicesStep.ShowCode>(model.state.value.step)
        assertEquals(listOf("load", "refill", "add:tablet", "load"), log)
    }

    @Test
    fun `declining the restart changes nothing`() {
        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = true))
        val model = viewModel()
        model.add("tablet")

        model.dismiss()

        assertNull(model.state.value.step)
        assertFalse("refill" in log)
    }

    @Test
    fun `a failed restart reports it and adds nothing`() {
        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = true))
        relay.refillResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Failed))
        val model = viewModel()
        model.add("tablet")

        model.restartAndAdd()

        assertNull(model.state.value.step)
        assertIs<UiText.Resource>(model.state.value.error)
        assertFalse("add:tablet" in log)
    }

    @Test
    fun `renaming saves the new name and refreshes`() {
        val model = viewModel()
        model.startRename("phone")

        model.rename("phone", "tablet")

        assertNull(model.state.value.step)
        assertEquals(listOf("load", "rename:phone:tablet", "load"), log)
    }

    @Test
    fun `cutting a device off waits for the confirm`() {
        val model = viewModel()

        model.askCutOff("phone")
        assertEquals(DevicesStep.ConfirmCutOff("phone"), model.state.value.step)
        assertFalse("cutOff:phone" in log)

        model.cutOff()

        assertNull(model.state.value.step)
        assertEquals(listOf("load", "cutOff:phone", "load"), log)
    }

    @Test
    fun `a new code waits for the confirm and then shows`() {
        val model = viewModel()

        model.askNewCode("phone")
        assertFalse("newCode:phone" in log)
        model.newCode()

        assertEquals(DevicesStep.ShowCode("phone", CODE), model.state.value.step)
    }

    @Test
    fun `an expired code is replaced from the code panel`() {
        val model = viewModel()
        model.add("tablet")

        model.newCode()

        assertEquals(listOf("load", "add:tablet", "load", "newCode:tablet", "load"), log)
    }

    @Test
    fun `a refusal shows the relay's own words`() {
        relay.cutOffResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Refused, "this key's role may not do that"))
        val model = viewModel()
        model.askCutOff("phone")

        model.cutOff()

        assertEquals(UiText.Dynamic("this key's role may not do that"), model.state.value.error)
    }

    @Test
    fun `the list reloads when the tunnel or key changes`() {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        relay.changes = changes
        viewModel()

        changes.tryEmit(Unit)
        changes.tryEmit(Unit)

        assertEquals(listOf("load", "load"), log)
    }
}

internal class FakeDevices(private val log: MutableList<String>) : RelayDevices {
    var board = Result.success(DeviceBoard(listOf(LAPTOP, PHONE), self = "laptop", admin = true, poolEmpty = false))
    var addResult = Result.success(CODE)
    var refillResult = Result.success(Unit)
    var cutOffResult = Result.success(Unit)
    var exitBoard = Result.success(ExitBoard(admin = true, exit = AgentExit.Direct))
    var setExitResult = Result.success(Unit)
    override var changes: Flow<Unit> = flowOf(Unit)

    override suspend fun load(): Result<DeviceBoard> {
        log += "load"
        return board
    }

    override suspend fun add(name: String): Result<PairingCode> {
        log += "add:$name"
        return addResult
    }

    override suspend fun rename(name: String, to: String): Result<Unit> {
        log += "rename:$name:$to"
        return Result.success(Unit)
    }

    override suspend fun cutOff(name: String): Result<Unit> {
        log += "cutOff:$name"
        return cutOffResult
    }

    override suspend fun newCode(name: String): Result<PairingCode> {
        log += "newCode:$name"
        return Result.success(CODE)
    }

    override suspend fun refillPool(): Result<Unit> {
        log += "refill"
        return refillResult
    }

    override suspend fun loadExit(): Result<ExitBoard> {
        log += "loadExit"
        return exitBoard
    }

    override suspend fun setExit(change: ExitChange): Result<Unit> {
        log += "setExit:${change::class.simpleName}"
        return setExitResult
    }
}
