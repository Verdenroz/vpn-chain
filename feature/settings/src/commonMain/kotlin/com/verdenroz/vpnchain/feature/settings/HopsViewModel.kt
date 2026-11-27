package com.verdenroz.vpnchain.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.data.WarpRepository
import com.verdenroz.vpnchain.core.domain.ChangeHopsUseCase
import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.Hop
import com.verdenroz.vpnchain.core.domain.SameKeyAsOtherHop
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_action_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_same_as_entry
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_same_as_exit
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_saved_not_connected
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

enum class HopCard { Entry, Exit }

sealed interface HopStep {
    data class Pasting(val error: UiText? = null) : HopStep

    /** [toString] hides the entry's key; only the endpoint is named. */
    data class Confirm(val entry: WireGuardEntry) : HopStep {
        override fun toString() = "Confirm(endpoint=${entry.endpointHost}:${entry.endpointPort})"
    }

    data object ConfirmRemove : HopStep
}

data class HopNotice(val card: HopCard, val text: UiText)

data class HopsUiState(
    val entry: WireGuardEntry? = null,
    val entryEnabled: Boolean = true,
    /** False where the chain does not dial the entry hop, which is desktop with Route all apps off. */
    val entryDialled: Boolean = true,
    val deviceExit: WireGuardEntry? = null,
    val warpCarriesAll: Boolean = false,
    val steps: Map<HopCard, HopStep> = emptyMap(),
    val busy: Boolean = false,
    val error: HopNotice? = null,
)

/**
 * Backs the Entry hop and Exit hop cards. A conf is parsed on review and only
 * the parsed hop is kept in [HopsUiState.steps]; the text is never stored.
 */
class HopsViewModel(
    profiles: ProfileRepository,
    settings: SettingsRepository,
    warp: WarpRepository,
    private val changeHops: ChangeHopsUseCase,
    private val changeRouting: ChangeRoutingUseCase,
) : ViewModel() {

    private data class Local(
        val steps: Map<HopCard, HopStep> = emptyMap(),
        val busy: Boolean = false,
        val error: HopNotice? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<HopsUiState> = combine(profiles.profile, settings.settings, warp.registered, local) { profile, user, warpRegistered, local ->
        HopsUiState(
            entry = profile?.wireGuard,
            entryEnabled = user.entryHopEnabled,
            entryDialled = user.systemWideTun,
            deviceExit = profile?.deviceExit,
            warpCarriesAll = user.warpMode == WarpMode.AllTraffic && warpRegistered,
            steps = local.steps,
            busy = local.busy,
            error = local.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HopsUiState())

    fun startPaste(card: HopCard) = open(card, HopStep.Pasting())

    fun askRemove(card: HopCard) = open(card, HopStep.ConfirmRemove)

    private val reviews = mutableMapOf<HopCard, Job>()

    fun dismiss(card: HopCard) {
        reviews.remove(card)?.cancel()
        local.update { it.copy(steps = it.steps - card, error = null) }
    }

    fun picked(card: HopCard, picked: PickedConf?) {
        when (picked) {
            null -> Unit
            is PickedConf.Text -> review(card, picked.text)
            else -> open(card, HopStep.Pasting(picked.problem()))
        }
    }

    fun review(card: HopCard, text: String) {
        reviews.remove(card)?.cancel()
        reviews[card] = viewModelScope.launch {
            val reviewed = when (card) {
                HopCard.Entry -> changeHops.reviewEntryHop(text)
                HopCard.Exit -> changeHops.reviewDeviceExit(text)
            }
            open(
                card,
                reviewed.fold(
                    onSuccess = { HopStep.Confirm(it) },
                    onFailure = { HopStep.Pasting(it.toText()) },
                ),
            )
        }
    }

    fun confirm(card: HopCard) {
        val change = when (val step = local.value.steps[card]) {
            is HopStep.Confirm -> step.entry
            HopStep.ConfirmRemove -> null
            else -> return
        }
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = when (card) {
                HopCard.Entry -> changeHops.setEntryHop(change)
                HopCard.Exit -> changeHops.setDeviceExit(change)
            }
            local.update {
                it.copy(
                    steps = it.steps - card,
                    busy = false,
                    error = saved.fold(
                        onSuccess = { done -> notice(card, Res.string.hops_saved_not_connected).takeIf { done.reconnect.isFailure } },
                        onFailure = { notice(card, Res.string.devices_action_failed) },
                    ),
                )
            }
        }
    }

    fun setEntryHopEnabled(enabled: Boolean) {
        viewModelScope.launch {
            changeRouting.setEntryHopEnabled(enabled).onFailure {
                local.update { it.copy(error = notice(HopCard.Entry, Res.string.hops_saved_not_connected)) }
            }
        }
    }

    private fun open(card: HopCard, step: HopStep) =
        local.update { it.copy(steps = it.steps + (card to step), error = null) }

    private fun notice(card: HopCard, text: StringResource) =
        HopNotice(card, UiText.Resource(text))

    private fun Throwable.toText(): UiText = when ((this as? SameKeyAsOtherHop)?.other) {
        Hop.Entry -> UiText.Resource(Res.string.hops_same_as_entry)
        Hop.Exit -> UiText.Resource(Res.string.hops_same_as_exit)
        null -> UiText.Dynamic(message.orEmpty())
    }
}
