package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.ExitBoard
import com.verdenroz.vpnchain.feature.settings.ExitUiState
import com.verdenroz.vpnchain.feature.settings.HopCard
import com.verdenroz.vpnchain.feature.settings.HopStep
import com.verdenroz.vpnchain.feature.settings.HopsUiState
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_change
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_confirm
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_confirm_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_paste_hint
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_relay
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_warp_all
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_this_device
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_current
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_switch
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_use_different
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ExitHopCard(
    hops: HopsUiState,
    device: HopActions,
    everyDevice: ExitState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ThisDevice(hops, device)
        if (everyDevice.state.board != ExitBoard.Hidden) {
            Spacer(Modifier.height(18.dp))
            ExitSection(everyDevice.state, everyDevice.actions, deviceOverrides = hops.deviceExit != null)
        }
    }
}

internal class ExitState(val state: ExitUiState, val actions: ExitActions)

@Composable
private fun ThisDevice(state: HopsUiState, actions: HopActions) {
    val colors = VpnChainTheme.colors
    val exit = state.deviceExit
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.exit_this_device), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Text(
            if (exit == null) {
                stringResource(Res.string.exit_device_relay)
            } else {
                stringResource(Res.string.hops_current, exit.provider ?: "WireGuard", exit.endpoint())
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
        if (exit != null && state.warpCarriesAll) {
            Text(stringResource(Res.string.exit_device_warp_all), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
        Spacer(Modifier.height(10.dp))
        state.error?.takeIf { it.card == HopCard.Exit }?.let {
            Text(it.text.asString(), style = MaterialTheme.typography.bodyMedium, color = colors.unprotectedText)
            Spacer(Modifier.height(10.dp))
        }
        when (val step = state.steps[HopCard.Exit]) {
            null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    compact = true,
                    text = stringResource(if (exit == null) Res.string.exit_change else Res.string.hops_use_different),
                    onClick = actions.onStart,
                    enabled = !state.busy,
                )
                if (exit != null) {
                    ActionButton(compact = true, text = stringResource(Res.string.hops_remove), onClick = actions.onAskRemove, enabled = !state.busy)
                }
            }
            is HopStep.Pasting -> ConfPasteForm(
                hint = stringResource(Res.string.exit_device_paste_hint),
                error = step.error,
                onReview = actions.onReview,
                onChoose = actions.onChoose,
                onCancel = actions.onDismiss,
            )
            is HopStep.Confirm -> HopConfirm(
                question = stringResource(Res.string.exit_device_confirm, step.entry.endpoint()),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.hops_switch),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
            HopStep.ConfirmRemove -> HopConfirm(
                question = stringResource(Res.string.exit_device_confirm_remove),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.hops_remove),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
        }
    }
}
