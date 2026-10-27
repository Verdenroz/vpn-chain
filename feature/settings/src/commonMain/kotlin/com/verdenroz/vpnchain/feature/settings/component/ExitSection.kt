package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.ValueField
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.ExitBoard
import com.verdenroz.vpnchain.feature.settings.ExitStep
import com.verdenroz.vpnchain.feature.settings.ExitUiState
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_working
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_change
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_confirm_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_confirm_wireguard
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_conf_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_paste_hint
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_review
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_switch
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_use_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_wireguard
import org.jetbrains.compose.resources.stringResource

internal class ExitActions(
    val onStartChange: () -> Unit,
    val onReview: (String) -> Unit,
    val onAskDirect: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Where the relay's traffic leaves, for an admin. Nothing is drawn for a member. */
@Composable
internal fun ExitSection(state: ExitUiState, actions: ExitActions, modifier: Modifier = Modifier) {
    val board = state.board as? ExitBoard.Ready ?: return
    val colors = VpnChainTheme.colors
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.exit_label), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Text(
            when (val exit = board.exit) {
                AgentExit.Direct -> stringResource(Res.string.exit_direct)
                is AgentExit.WireGuard -> stringResource(Res.string.exit_wireguard, exit.endpoint)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
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
            is ExitStep.Pasting -> PasteForm(step, actions)
            is ExitStep.ConfirmWireGuard -> Confirm(
                question = stringResource(Res.string.exit_confirm_wireguard, step.endpoint),
                busy = state.busy,
                actions = actions,
            )
            ExitStep.ConfirmDirect -> Confirm(
                question = stringResource(Res.string.exit_confirm_direct),
                busy = state.busy,
                actions = actions,
            )
        }
    }
}

@Composable
private fun PasteForm(step: ExitStep.Pasting, actions: ExitActions) {
    var pasted by remember { mutableStateOf("") }
    val colors = VpnChainTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.exit_paste_hint), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        Spacer(Modifier.height(10.dp))
        ValueField(
            label = stringResource(Res.string.exit_conf_label),
            value = pasted,
            minLines = 4,
            sensitive = true,
            onValueChange = { pasted = it },
        )
        step.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = colors.unprotectedText)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.exit_review),
                onClick = { actions.onReview(pasted) },
                enabled = pasted.isNotBlank(),
                tone = ActionTone.Primary,
            )
            ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = actions.onDismiss)
        }
    }
}

@Composable
private fun Confirm(question: String, busy: Boolean, actions: ExitActions) {
    val colors = VpnChainTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(question, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Spacer(Modifier.height(10.dp))
        if (busy) {
            Text(stringResource(Res.string.devices_working), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(compact = true, text = stringResource(Res.string.exit_switch), onClick = actions.onConfirm, tone = ActionTone.Rerouted)
                ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = actions.onDismiss)
            }
        }
    }
}
