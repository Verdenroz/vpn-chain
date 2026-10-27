package com.verdenroz.vpnchain.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.domain.DeviceBoard
import com.verdenroz.vpnchain.core.domain.DevicesFailed
import com.verdenroz.vpnchain.core.domain.RelayDevices
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.PairingCode
import com.verdenroz.vpnchain.core.relay.isDeviceName
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_action_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_name_invalid
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_name_taken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceRow(
    val name: String,
    val role: DeviceRole,
    val bytes: Long,
    val seen: String?,
    val self: Boolean,
)

/** What the Devices card has to show; [Unavailable] is one plain line saying why. */
sealed interface DevicesBoard {
    data object Loading : DevicesBoard
    data class Unavailable(val why: Why) : DevicesBoard
    data class Ready(val devices: List<DeviceRow>, val admin: Boolean, val poolEmpty: Boolean) : DevicesBoard

    enum class Why { NoKey, Offline, Failed }
}

/** The one question or result the card puts in front of the list. */
sealed interface DevicesStep {
    data class Naming(val error: UiText? = null) : DevicesStep
    data class Renaming(val name: String, val error: UiText? = null) : DevicesStep
    data class ConfirmCutOff(val name: String) : DevicesStep
    data class ConfirmNewCode(val name: String) : DevicesStep

    /** Adding [then] needs the spare pool refilled first, which restarts the relay. */
    data class ConfirmRestart(val then: String) : DevicesStep

    data class ShowCode(val name: String, val code: PairingCode) : DevicesStep
}

data class DevicesUiState(
    val board: DevicesBoard = DevicesBoard.Loading,
    val step: DevicesStep? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
)

/**
 * Lists the relay's devices and changes them. A pairing code lives only in [DevicesUiState.step]
 * and is dropped as soon as the step is, so it is never saved or logged.
 */
class DevicesViewModel(private val devices: RelayDevices) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            devices.changes.collect { if (!_state.value.busy) refresh() }
        }
    }

    fun startAdd() = open(DevicesStep.Naming())

    fun startRename(name: String) = open(DevicesStep.Renaming(name))

    fun askCutOff(name: String) = open(DevicesStep.ConfirmCutOff(name))

    fun askNewCode(name: String) = open(DevicesStep.ConfirmNewCode(name))

    fun dismiss() {
        _state.update { it.copy(step = null, error = null) }
        viewModelScope.launch { refresh() }
    }

    fun add(name: String) {
        if (!isDeviceName(name)) return rejectName(UiText.Resource(Res.string.devices_name_invalid))
        val board = _state.value.board
        if (board is DevicesBoard.Ready && board.poolEmpty) {
            _state.update { it.copy(step = DevicesStep.ConfirmRestart(name), error = null) }
        } else {
            act { addNow(name) }
        }
    }

    fun restartAndAdd() {
        val then = (_state.value.step as? DevicesStep.ConfirmRestart)?.then ?: return
        act { devices.refillPool().fold(onSuccess = { addNow(then) }, onFailure = ::fail) }
    }

    fun rename(name: String, to: String) {
        if (!isDeviceName(to)) return rejectName(UiText.Resource(Res.string.devices_name_invalid))
        act {
            devices.rename(name, to).fold(
                onSuccess = { closeStep() },
                onFailure = {
                    if ((it as? DevicesFailed)?.reason == DevicesFailed.Reason.NameTaken) {
                        rejectName(UiText.Resource(Res.string.devices_name_taken))
                    } else {
                        fail(it)
                    }
                },
            )
        }
    }

    fun cutOff() {
        val name = (_state.value.step as? DevicesStep.ConfirmCutOff)?.name ?: return
        act { devices.cutOff(name).fold(onSuccess = { closeStep() }, onFailure = ::fail) }
    }

    /** Issues the code for the device the open step names, whether it was confirmed or has expired. */
    fun newCode() {
        val name = when (val step = _state.value.step) {
            is DevicesStep.ConfirmNewCode -> step.name
            is DevicesStep.ShowCode -> step.name
            else -> return
        }
        act { devices.newCode(name).fold(onSuccess = { showCode(name, it) }, onFailure = ::fail) }
    }

    private suspend fun addNow(name: String) {
        devices.add(name).fold(
            onSuccess = { showCode(name, it) },
            onFailure = {
                when ((it as? DevicesFailed)?.reason) {
                    DevicesFailed.Reason.PoolEmpty -> _state.update { state -> state.copy(step = DevicesStep.ConfirmRestart(name)) }
                    DevicesFailed.Reason.NameTaken -> rejectName(UiText.Resource(Res.string.devices_name_taken))
                    else -> fail(it)
                }
            },
        )
    }

    private fun open(step: DevicesStep) = _state.update { it.copy(step = step, error = null) }

    private fun rejectName(error: UiText) = _state.update { it.copy(step = it.step?.withError(error), error = null) }

    private fun DevicesStep.withError(error: UiText) = when (this) {
        is DevicesStep.Naming -> copy(error = error)
        is DevicesStep.Renaming -> copy(error = error)
        else -> this
    }

    private fun showCode(name: String, code: PairingCode) =
        _state.update { it.copy(step = DevicesStep.ShowCode(name, code), error = null) }

    private fun closeStep() = _state.update { it.copy(step = null) }

    private fun fail(failure: Throwable) {
        val text = (failure as? DevicesFailed)?.takeIf { it.reason == DevicesFailed.Reason.Refused }?.message
        _state.update { it.copy(step = null, error = text?.let(UiText::Dynamic) ?: UiText.Resource(Res.string.devices_action_failed)) }
    }

    private fun act(work: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            work()
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun refresh() {
        val board = devices.load().fold(onSuccess = { it.toBoard() }, onFailure = { it.toUnavailable() })
        _state.update { it.copy(board = board) }
    }

    private fun DeviceBoard.toBoard() = DevicesBoard.Ready(
        devices = devices.map { DeviceRow(it.name, it.role, it.up + it.down, it.seen, self = it.name == self) },
        admin = admin,
        poolEmpty = poolEmpty,
    )

    private fun Throwable.toUnavailable() = DevicesBoard.Unavailable(
        when ((this as? DevicesFailed)?.reason) {
            DevicesFailed.Reason.NoKey -> DevicesBoard.Why.NoKey
            DevicesFailed.Reason.Offline -> DevicesBoard.Why.Offline
            else -> DevicesBoard.Why.Failed
        },
    )
}
