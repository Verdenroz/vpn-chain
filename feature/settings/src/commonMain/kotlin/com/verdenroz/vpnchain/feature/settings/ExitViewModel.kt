package com.verdenroz.vpnchain.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.domain.DevicesFailed
import com.verdenroz.vpnchain.core.domain.RelayDevices
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.relay.ExitChange
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_action_failed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Exit row shows. It is [Hidden] for a member, and whenever the relay cannot be asked. */
sealed interface ExitBoard {
    data object Hidden : ExitBoard
    data class Ready(val exit: AgentExit) : ExitBoard
}

/** The one question the row puts in front of the exit. */
sealed interface ExitStep {
    data class Pasting(val error: UiText? = null) : ExitStep

    /** [conf] holds the provider's private key, so [toString] hides it. */
    data class ConfirmWireGuard(val conf: String, val endpoint: String) : ExitStep {
        override fun toString() = "ConfirmWireGuard(endpoint=$endpoint)"
    }

    data object ConfirmDirect : ExitStep
}

data class ExitUiState(
    val board: ExitBoard = ExitBoard.Hidden,
    val step: ExitStep? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
)

/**
 * Shows the relay's exit to an admin and switches it. A pasted conf lives only in
 * [ExitUiState.step] and is dropped as soon as the step is, so it is never saved or logged.
 */
class ExitViewModel(private val devices: RelayDevices) : ViewModel() {

    private val _state = MutableStateFlow(ExitUiState())
    val state: StateFlow<ExitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            devices.changes.collect { if (!_state.value.busy) refresh() }
        }
    }

    fun startChange() = open(ExitStep.Pasting())

    fun askDirect() = open(ExitStep.ConfirmDirect)

    fun dismiss() {
        _state.update { it.copy(step = null, error = null) }
        viewModelScope.launch { refresh() }
    }

    fun review(text: String) {
        val step = WireGuardConfParser.parse(text).fold(
            onSuccess = { ExitStep.ConfirmWireGuard(text, "${it.endpointHost}:${it.endpointPort}") },
            onFailure = { ExitStep.Pasting(UiText.Dynamic(it.message.orEmpty())) },
        )
        _state.update { it.copy(step = step, error = null) }
    }

    fun confirm() {
        val change = when (val step = _state.value.step) {
            is ExitStep.ConfirmWireGuard -> ExitChange.WireGuard(step.conf)
            ExitStep.ConfirmDirect -> ExitChange.Direct
            else -> return
        }
        act { devices.setExit(change).fold(onSuccess = { closeStep() }, onFailure = ::fail) }
    }

    private fun open(step: ExitStep) = _state.update { it.copy(step = step, error = null) }

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
        val board = devices.loadExit().fold(
            onSuccess = { board -> board.exit?.let { ExitBoard.Ready(it) } ?: ExitBoard.Hidden },
            onFailure = { ExitBoard.Hidden },
        )
        _state.update { it.copy(board = board) }
    }
}
