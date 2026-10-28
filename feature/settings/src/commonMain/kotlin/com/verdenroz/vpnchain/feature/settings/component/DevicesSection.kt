package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.common.formatBytes
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.ValueField
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.PairingCode
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.DevicePending
import com.verdenroz.vpnchain.feature.settings.DeviceRow
import com.verdenroz.vpnchain.feature.settings.DevicesBoard
import com.verdenroz.vpnchain.feature.settings.DevicesStep
import com.verdenroz.vpnchain.feature.settings.DevicesUiState
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_add
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_code_expired
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_code_expires
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_code_intro
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_code_warning
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_confirm_cut_off
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_confirm_new_code
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_confirm_restart
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_copy_link
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_cut_off
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_dismiss_notice
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_entry_hop_intro
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_entry_hop_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_entry_hop_note
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_done
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_last_seen
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_loading
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_name_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_never_seen
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_new_code
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_no_key
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_offline
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_pending_cut_off
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_pending_new_code
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_pending_rename
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_rename
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_restart_and_add
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_role_admin
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_role_member
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_save_name
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_share_link
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_skip
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_show_code
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_this_device
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_traffic
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_working
import com.verdenroz.vpnchain.feature.settings.rememberTextSharer
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

internal class DevicesActions(
    val onAdd: (String) -> Unit,
    val onSubmitEntryHop: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onRestartAndAdd: () -> Unit,
    val onCutOff: () -> Unit,
    val onNewCode: () -> Unit,
    val onStartAdd: () -> Unit,
    val onStartRename: (String) -> Unit,
    val onAskCutOff: (String) -> Unit,
    val onAskNewCode: (String) -> Unit,
    val onDismiss: () -> Unit,
    val onDismissNotice: () -> Unit,
)

/** The relay's devices, and the step an admin is in the middle of. A member sees only its own row. */
@Composable
internal fun DevicesSection(state: DevicesUiState, actions: DevicesActions, modifier: Modifier = Modifier) {
    val colors = VpnChainTheme.colors
    Column(modifier.fillMaxWidth()) {
        state.error?.let {
            SelectionContainer {
                Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = colors.unprotectedText)
            }
            Spacer(Modifier.height(12.dp))
        }
        state.notice?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = colors.ink, modifier = Modifier.weight(1f))
                ActionButton(compact = true, text = stringResource(Res.string.devices_dismiss_notice), onClick = actions.onDismissNotice)
            }
            Spacer(Modifier.height(12.dp))
        }
        val step = state.step
        if (step != null) {
            StepPanel(step, state.busy, actions)
            return@Column
        }
        when (val board = state.board) {
            DevicesBoard.Loading -> Muted(stringResource(Res.string.devices_loading))
            is DevicesBoard.Unavailable -> Muted(
                stringResource(
                    when (board.why) {
                        DevicesBoard.Why.NoKey -> Res.string.devices_no_key
                        DevicesBoard.Why.Offline -> Res.string.devices_offline
                        DevicesBoard.Why.Failed -> Res.string.devices_failed
                    },
                ),
            )
            is DevicesBoard.Ready -> {
                board.devices.forEachIndexed { index, device ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    DeviceRowView(device, board.admin && !state.busy, state.pending?.takeIf { it.name == device.name }, actions)
                }
                if (board.admin) {
                    Spacer(Modifier.height(14.dp))
                    ActionButton(
                        compact = true,
                        text = stringResource(Res.string.devices_add),
                        onClick = actions.onStartAdd,
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = VpnChainTheme.colors.inkMuted)
}

@Composable
private fun DeviceRowView(device: DeviceRow, canManage: Boolean, pending: DevicePending?, actions: DevicesActions) {
    val colors = VpnChainTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 1)
            if (device.self) {
                Text(
                    stringResource(Res.string.devices_this_device),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.connectedText,
                )
            }
        }
        Text(
            listOf(
                stringResource(if (device.role == DeviceRole.Admin) Res.string.devices_role_admin else Res.string.devices_role_member),
                stringResource(Res.string.devices_traffic, formatBytes(device.bytes)),
                device.seen?.let { stringResource(Res.string.devices_last_seen, readable(it)) }
                    ?: stringResource(Res.string.devices_never_seen),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        if (pending != null) {
            Spacer(Modifier.height(8.dp))
            Muted(
                stringResource(
                    when (pending.action) {
                        DevicePending.Action.CuttingOff -> Res.string.devices_pending_cut_off
                        DevicePending.Action.Renaming -> Res.string.devices_pending_rename
                        DevicePending.Action.NewCode -> Res.string.devices_pending_new_code
                    },
                ),
            )
        } else if (canManage && !device.self) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(compact = true, text = stringResource(Res.string.devices_rename), onClick = { actions.onStartRename(device.name) })
                ActionButton(compact = true, text = stringResource(Res.string.devices_new_code), onClick = { actions.onAskNewCode(device.name) })
                ActionButton(compact = true, text = stringResource(Res.string.devices_cut_off), onClick = { actions.onAskCutOff(device.name) })
            }
        }
    }
}

/** `2026-10-26T07:33:20Z` as `2026-10-26 07:33 UTC`. */
private fun readable(seen: String) = seen.take(16).replace('T', ' ') + " UTC"

@Composable
private fun StepPanel(step: DevicesStep, busy: Boolean, actions: DevicesActions) {
    when (step) {
        is DevicesStep.Naming -> NameForm(
            initial = step.name,
            error = step.error,
            submit = stringResource(Res.string.devices_show_code),
            busy = busy,
            onSubmit = actions.onAdd,
            onCancel = actions.onDismiss,
        )
        is DevicesStep.EntryHop -> EntryHopForm(step, busy, actions.onSubmitEntryHop, actions.onDismiss)
        is DevicesStep.Renaming -> NameForm(
            initial = step.name,
            error = step.error,
            submit = stringResource(Res.string.devices_save_name),
            busy = busy,
            onSubmit = { actions.onRename(step.name, it) },
            onCancel = actions.onDismiss,
        )
        is DevicesStep.ConfirmCutOff -> Confirm(
            question = stringResource(Res.string.devices_confirm_cut_off, step.name),
            action = stringResource(Res.string.devices_cut_off),
            tone = ActionTone.Danger,
            busy = busy,
            onConfirm = actions.onCutOff,
            onCancel = actions.onDismiss,
        )
        is DevicesStep.ConfirmNewCode -> Confirm(
            question = stringResource(Res.string.devices_confirm_new_code, step.name),
            action = stringResource(Res.string.devices_new_code),
            tone = ActionTone.Rerouted,
            busy = busy,
            onConfirm = actions.onNewCode,
            onCancel = actions.onDismiss,
        )
        is DevicesStep.ConfirmRestart -> Confirm(
            question = stringResource(Res.string.devices_confirm_restart, step.then),
            action = stringResource(Res.string.devices_restart_and_add),
            tone = ActionTone.Rerouted,
            busy = busy,
            onConfirm = actions.onRestartAndAdd,
            onCancel = actions.onDismiss,
        )
        is DevicesStep.ShowCode -> CodePanel(step.name, step.code, busy, actions)
    }
}

@Composable
private fun NameForm(
    initial: String,
    error: UiText?,
    submit: String,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth()) {
        ValueField(label = stringResource(Res.string.devices_name_label), value = name, onValueChange = { name = it.trim() })
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = VpnChainTheme.colors.unprotectedText)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = submit,
                onClick = { onSubmit(name) },
                enabled = name.isNotEmpty() && !busy,
                tone = ActionTone.Primary,
            )
            ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = onCancel, enabled = !busy)
        }
    }
}

@Composable
private fun EntryHopForm(step: DevicesStep.EntryHop, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val colors = VpnChainTheme.colors
    var conf by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.devices_entry_hop_intro, step.name), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.devices_entry_hop_note), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Spacer(Modifier.height(10.dp))
        ValueField(
            label = stringResource(Res.string.devices_entry_hop_label),
            value = conf,
            sensitive = true,
            minLines = 5,
            onValueChange = { conf = it },
        )
        step.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = colors.unprotectedText)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.devices_show_code),
                onClick = { onSubmit(conf) },
                enabled = conf.isNotBlank() && !busy,
                tone = ActionTone.Primary,
            )
            ActionButton(compact = true, text = stringResource(Res.string.devices_skip), onClick = { onSubmit("") }, enabled = !busy)
            ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = onCancel, enabled = !busy)
        }
    }
}

@Composable
private fun Confirm(
    question: String,
    action: String,
    tone: ActionTone,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(question, style = MaterialTheme.typography.bodyMedium, color = VpnChainTheme.colors.ink)
        Spacer(Modifier.height(10.dp))
        if (busy) {
            Muted(stringResource(Res.string.devices_working))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(compact = true, text = action, onClick = onConfirm, tone = tone)
                ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = onCancel)
            }
        }
    }
}

@Composable
private fun CodePanel(name: String, code: PairingCode, busy: Boolean, actions: DevicesActions) {
    val colors = VpnChainTheme.colors
    val now by produceState(currentTimeMillis() / 1000) {
        while (true) {
            delay(1000)
            value = currentTimeMillis() / 1000
        }
    }
    val left = code.expires - now
    val clipboard = LocalClipboardManager.current
    val share = rememberTextSharer()
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.devices_code_intro, name), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.devices_code_warning), style = MaterialTheme.typography.bodySmall, color = colors.reroutedText)
        Spacer(Modifier.height(12.dp))
        if (left > 0) {
            QrImage(code.link)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.devices_code_expires, countdown(left)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        } else {
            Text(stringResource(Res.string.devices_code_expired), style = MaterialTheme.typography.bodyMedium, color = colors.unprotectedText)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (left > 0) {
                ActionButton(
                    compact = true,
                    text = stringResource(Res.string.devices_copy_link),
                    onClick = { clipboard.setText(AnnotatedString(code.link)) },
                )
                share?.let {
                    ActionButton(compact = true, text = stringResource(Res.string.devices_share_link), onClick = { it(code.link) })
                }
            } else {
                ActionButton(
                    compact = true,
                    text = stringResource(Res.string.devices_new_code),
                    onClick = actions.onNewCode,
                    enabled = !busy,
                    tone = ActionTone.Primary,
                )
            }
            ActionButton(compact = true, text = stringResource(Res.string.devices_done), onClick = actions.onDismiss)
        }
    }
}

private fun countdown(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
