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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_cut_off_done
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_renamed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.time.Duration.Companion.seconds

private val LAPTOP = AgentDevice("laptop", DeviceRole.Admin, added = "2026-10-01T00:00:00Z", up = 1_000, down = 2_000, seen = "2026-10-26T07:33:20Z")
private val PHONE = AgentDevice("phone", DeviceRole.Member, added = "2026-10-02T00:00:00Z", up = 0, down = 0)
private const val CONF = "[Interface]\nPrivateKey = FAKEKEY"
private val CODE = PairingCode("vpnchain://pair?v=1&t=SECRETTOKEN", expires = 2_000)

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val log = mutableListOf<String>()
    private val relay = FakeDevices(log)

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

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
        model.submitEntryHop("")

        val step = assertIs<DevicesStep.ShowCode>(model.state.value.step)
        assertEquals(CODE, step.code)
        assertEquals("tablet", step.name)
        assertEquals(listOf("load", "add:tablet", "load"), log)
    }

    @Test
    fun `a name moves on to the entry hop question and sends nothing yet`() {
        val model = viewModel()
        model.startAdd()

        model.add("tablet")

        assertEquals(DevicesStep.EntryHop("tablet"), model.state.value.step)
        assertEquals(listOf("load"), log)
    }

    @Test
    fun `an entry hop is checked and sent with the device`() {
        val model = viewModel()
        model.add("tablet")

        model.submitEntryHop("  $CONF\n")

        assertIs<DevicesStep.ShowCode>(model.state.value.step)
        assertEquals(listOf("load", "check", "add:tablet:entry", "load"), log)
        assertFalse("PrivateKey" in model.state.value.toString())
    }

    @Test
    fun `an entry hop the app refuses keeps the question open with the reason and adds nothing`() {
        relay.checkResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Refused, "Config has no peer."))
        val model = viewModel()
        model.add("tablet")

        model.submitEntryHop(CONF)

        assertEquals(DevicesStep.EntryHop("tablet", UiText.Dynamic("Config has no peer.")), model.state.value.step)
        assertFalse(model.state.value.busy)
        assertFalse("add:tablet" in log)
    }

    @Test
    fun `skipping the entry hop adds the device without one or a check`() {
        val model = viewModel()
        model.add("tablet")

        model.submitEntryHop("   ")

        assertEquals(listOf("load", "add:tablet", "load"), log)
    }

    @Test
    fun `an entry hop survives the relay restart an empty pool needs`() {
        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = true))
        val model = viewModel()
        model.add("tablet")

        model.submitEntryHop(CONF)
        assertEquals(DevicesStep.ConfirmRestart("tablet"), model.state.value.step)
        model.restartAndAdd()

        assertTrue("add:tablet:entry" in log)
    }

    @Test
    fun `a taken name goes back to the name form with what was typed`() {
        relay.addResult = Result.failure(DevicesFailed(DevicesFailed.Reason.NameTaken))
        val model = viewModel()
        model.add("phone")

        model.submitEntryHop(CONF)

        val step = assertIs<DevicesStep.Naming>(model.state.value.step)
        assertEquals("phone", step.name)
        assertIs<UiText.Resource>(step.error)
    }

    @Test
    fun `closing the code drops it`() {
        val model = viewModel()
        model.add("tablet")
        model.submitEntryHop("")

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
        model.submitEntryHop("")

        assertIs<UiText.Resource>(assertIs<DevicesStep.Naming>(model.state.value.step).error)
    }

    @Test
    fun `an empty pool asks before restarting, then restarts and adds`() {
        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = true))
        val model = viewModel()

        model.add("tablet")
        model.submitEntryHop("")

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
        model.submitEntryHop("")

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
        model.submitEntryHop("")

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
        model.submitEntryHop("")

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
    fun `cutting off marks the row pending, then confirms and clears itself`() {
        val gate = CompletableDeferred<Unit>().also { relay.gate = it }
        val model = viewModel()
        model.askCutOff("phone")

        model.cutOff()

        assertEquals(DevicePending("phone", DevicePending.Action.CuttingOff), model.state.value.pending)
        assertNull(model.state.value.step)
        assertNull(model.state.value.notice)

        relay.board = Result.success(DeviceBoard(listOf(LAPTOP), self = "laptop", admin = true, poolEmpty = false))
        gate.complete(Unit)

        assertNull(model.state.value.pending)
        assertEquals(listOf("laptop"), model.ready().devices.map { it.name })
        assertEquals(UiText.Resource(Res.string.devices_cut_off_done, listOf("phone")), model.state.value.notice)

        dispatcher.scheduler.advanceTimeBy(7.seconds)

        assertNull(model.state.value.notice)
    }

    @Test
    fun `a confirmed cut off can be dismissed`() {
        val model = viewModel()
        model.askCutOff("phone")
        model.cutOff()

        model.dismissNotice()

        assertNull(model.state.value.notice)
    }

    @Test
    fun `a failed cut off returns the row and says why`() {
        val gate = CompletableDeferred<Unit>().also { relay.gate = it }
        relay.cutOffResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Refused, "the relay did not restart"))
        val model = viewModel()
        model.askCutOff("phone")
        model.cutOff()

        gate.complete(Unit)

        assertNull(model.state.value.pending)
        assertNull(model.state.value.notice)
        assertEquals(UiText.Dynamic("the relay did not restart"), model.state.value.error)
        assertEquals(listOf("laptop", "phone"), model.ready().devices.map { it.name })
    }

    @Test
    fun `renaming marks the row pending and confirms the new name`() {
        val gate = CompletableDeferred<Unit>().also { relay.gate = it }
        val model = viewModel()
        model.startRename("phone")

        model.rename("phone", "tablet")

        assertEquals(DevicePending("phone", DevicePending.Action.Renaming), model.state.value.pending)
        assertNull(model.state.value.step)

        gate.complete(Unit)

        assertNull(model.state.value.pending)
        assertEquals(UiText.Resource(Res.string.devices_renamed, listOf("phone", "tablet")), model.state.value.notice)
    }

    @Test
    fun `a rename that is refused returns the row and reopens the form with a reason`() {
        relay.renameResult = Result.failure(DevicesFailed(DevicesFailed.Reason.NameTaken))
        val model = viewModel()
        model.startRename("phone")

        model.rename("phone", "laptop")

        assertNull(model.state.value.pending)
        assertNull(model.state.value.notice)
        val step = assertIs<DevicesStep.Renaming>(model.state.value.step)
        assertIs<UiText.Resource>(step.error)
    }

    @Test
    fun `a rename that fails otherwise returns the row with an error`() {
        relay.renameResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Offline))
        val model = viewModel()
        model.startRename("phone")

        model.rename("phone", "tablet")

        assertNull(model.state.value.pending)
        assertNull(model.state.value.step)
        assertIs<UiText.Resource>(model.state.value.error)
    }

    @Test
    fun `a new code marks the row pending until the code is ready`() {
        val gate = CompletableDeferred<Unit>().also { relay.gate = it }
        val model = viewModel()
        model.askNewCode("phone")

        model.newCode()

        assertEquals(DevicePending("phone", DevicePending.Action.NewCode), model.state.value.pending)
        assertNull(model.state.value.step)

        gate.complete(Unit)

        assertNull(model.state.value.pending)
        assertEquals(DevicesStep.ShowCode("phone", CODE), model.state.value.step)
    }

    @Test
    fun `a failed new code returns the row with an error`() {
        relay.newCodeResult = Result.failure(DevicesFailed(DevicesFailed.Reason.Failed))
        val model = viewModel()
        model.askNewCode("phone")

        model.newCode()

        assertNull(model.state.value.pending)
        assertNull(model.state.value.step)
        assertIs<UiText.Resource>(model.state.value.error)
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
    var checkResult = Result.success(Unit)
    var refillResult = Result.success(Unit)
    var cutOffResult = Result.success(Unit)
    var renameResult = Result.success(Unit)
    var newCodeResult = Result.success(CODE)
    var gate: CompletableDeferred<Unit>? = null
    var exitBoard = Result.success(ExitBoard(admin = true, exit = AgentExit.Direct))
    var setExitResult = Result.success(Unit)
    override var changes: Flow<Unit> = flowOf(Unit)

    override suspend fun load(): Result<DeviceBoard> {
        log += "load"
        return board
    }

    override suspend fun checkEntryHop(conf: String): Result<Unit> {
        log += "check"
        return checkResult
    }

    override suspend fun add(name: String, entryHop: String?): Result<PairingCode> {
        log += "add:$name" + if (entryHop != null) ":entry" else ""
        return addResult
    }

    override suspend fun rename(name: String, to: String): Result<Unit> {
        log += "rename:$name:$to"
        gate?.await()
        return renameResult
    }

    override suspend fun cutOff(name: String): Result<Unit> {
        log += "cutOff:$name"
        gate?.await()
        return cutOffResult
    }

    override suspend fun newCode(name: String): Result<PairingCode> {
        log += "newCode:$name"
        gate?.await()
        return newCodeResult
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
