package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.ExitBoard
import com.verdenroz.vpnchain.feature.settings.ExitStep
import com.verdenroz.vpnchain.feature.settings.ExitUiState
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_change
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_confirm_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_device_overrides
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_every_device
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_every_offline
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_confirm_wireguard
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_paste_hint
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_switch
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_use_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_wireguard
import org.jetbrains.compose.resources.stringResource

internal class ExitActions(
    val onStartChange: () -> Unit,
    val onReview: (String) -> Unit,
    val onPick: () -> Unit,
    val onAskDirect: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun ExitSection(
    state: ExitUiState,
    actions: ExitActions,
    deviceOverrides: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state.board == ExitBoard.Hidden) return
    val colors = VpnChainTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.exit_every_device), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        val board = state.board as? ExitBoard.Ready
        if (board == null) {
            Text(stringResource(Res.string.exit_every_offline), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            return
        }
        Text(
            when (val exit = board.exit) {
                AgentExit.Direct -> stringResource(Res.string.exit_direct)
                is AgentExit.WireGuard -> stringResource(Res.string.exit_wireguard, exit.endpoint)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
        if (deviceOverrides) {
            Text(stringResource(Res.string.exit_device_overrides), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
        Spacer(Modifier.height(10.dp))
        state.error?.let {
            SelectionContainer {
                Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = colors.unprotectedText)
            }
            Spacer(Modifier.height(10.dp))
        }
        when (val step = state.step) {
            null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(compact = true, text = stringResource(Res.string.exit_change), onClick = actions.onStartChange, enabled = !state.busy)
                if (board.exit is AgentExit.WireGuard) {
                    ActionButton(compact = true, text = stringResource(Res.string.exit_use_direct), onClick = actions.onAskDirect, enabled = !state.busy)
                }
            }
            is ExitStep.Pasting -> ConfPasteForm(
                hint = stringResource(Res.string.exit_paste_hint),
                error = step.error,
                onReview = actions.onReview,
                onChoose = actions.onPick,
                onCancel = actions.onDismiss,
            )
            is ExitStep.ConfirmWireGuard -> HopConfirm(
                question = stringResource(Res.string.exit_confirm_wireguard, step.endpoint),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.exit_switch),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
            ExitStep.ConfirmDirect -> HopConfirm(
                question = stringResource(Res.string.exit_confirm_direct),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.exit_switch),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
        }
    }
}
