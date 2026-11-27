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
import com.verdenroz.vpnchain.feature.settings.HopCard
import com.verdenroz.vpnchain.feature.settings.HopStep
import com.verdenroz.vpnchain.feature.settings.HopsUiState
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_current
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_confirm
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_confirm_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_none
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_paste_hint
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_unused
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_switch
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_use_config
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_use_different
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_title
import org.jetbrains.compose.resources.stringResource

internal class HopActions(
    val onStart: () -> Unit,
    val onAskRemove: () -> Unit,
    val onReview: (String) -> Unit,
    val onChoose: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun EntryHopCard(
    state: HopsUiState,
    actions: HopActions,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VpnChainTheme.colors
    val entry = state.entry
    Column(modifier.fillMaxWidth()) {
        Text(
            if (entry == null) {
                stringResource(Res.string.hops_entry_none)
            } else {
                stringResource(Res.string.hops_current, entry.provider ?: "WireGuard", entry.endpoint())
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry == null) colors.inkMuted else colors.ink,
        )
        if (entry != null && !state.entryDialled) {
            Text(stringResource(Res.string.hops_entry_unused), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
        if (entry != null) {
            Spacer(Modifier.height(14.dp))
            SettingRow(
                title = stringResource(Res.string.settings_entry_hop_title),
                detail = stringResource(Res.string.settings_entry_hop_detail),
                checked = state.entryEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }
        Spacer(Modifier.height(14.dp))
        state.error?.takeIf { it.card == HopCard.Entry }?.let {
            Text(it.text.asString(), style = MaterialTheme.typography.bodyMedium, color = colors.unprotectedText)
            Spacer(Modifier.height(10.dp))
        }
        when (val step = state.steps[HopCard.Entry]) {
            null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    compact = true,
                    text = stringResource(if (entry == null) Res.string.hops_use_config else Res.string.hops_use_different),
                    onClick = actions.onStart,
                    enabled = !state.busy,
                )
                if (entry != null) {
                    ActionButton(compact = true, text = stringResource(Res.string.hops_remove), onClick = actions.onAskRemove, enabled = !state.busy)
                }
            }
            is HopStep.Pasting -> ConfPasteForm(
                hint = stringResource(Res.string.hops_entry_paste_hint),
                error = step.error,
                onReview = actions.onReview,
                onChoose = actions.onChoose,
                onCancel = actions.onDismiss,
            )
            is HopStep.Confirm -> HopConfirm(
                question = stringResource(Res.string.hops_entry_confirm, step.entry.endpoint()),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.hops_switch),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
            HopStep.ConfirmRemove -> HopConfirm(
                question = stringResource(Res.string.hops_entry_confirm_remove, entry?.endpoint().orEmpty()),
                busy = state.busy,
                confirmLabel = stringResource(Res.string.hops_remove),
                onConfirm = actions.onConfirm,
                onCancel = actions.onDismiss,
            )
        }
    }
}
