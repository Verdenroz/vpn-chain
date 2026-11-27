package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_working
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_conf_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.exit_review
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_choose_file
import org.jetbrains.compose.resources.stringResource

/** A WireGuard conf, pasted or chosen from a file; both reach [onReview] or the caller's file picker. */
@Composable
internal fun ConfPasteForm(
    hint: String,
    error: UiText?,
    onReview: (String) -> Unit,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    val colors = VpnChainTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        Spacer(Modifier.height(10.dp))
        ValueField(
            label = stringResource(Res.string.exit_conf_label),
            value = pasted,
            minLines = 4,
            sensitive = true,
            onValueChange = { pasted = it },
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = colors.unprotectedText)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.exit_review),
                onClick = { onReview(pasted) },
                enabled = pasted.isNotBlank(),
                tone = ActionTone.Primary,
            )
            ActionButton(compact = true, text = stringResource(Res.string.hops_choose_file), onClick = onChoose)
            ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = onCancel)
        }
    }
}

/** Names what is about to change; nothing does until [onConfirm]. */
@Composable
internal fun HopConfirm(
    question: String,
    busy: Boolean,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = VpnChainTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(question, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Spacer(Modifier.height(10.dp))
        if (busy) {
            Text(stringResource(Res.string.devices_working), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(compact = true, text = confirmLabel, onClick = onConfirm, tone = ActionTone.Rerouted)
                ActionButton(compact = true, text = stringResource(Res.string.devices_cancel), onClick = onCancel)
            }
        }
    }
}
