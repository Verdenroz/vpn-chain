package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.common.Platform
import com.verdenroz.vpnchain.core.common.currentPlatform
import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.ValueField
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.feature.settings.PendingImport
import com.verdenroz.vpnchain.feature.settings.component.QrImage
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_as_entry_hop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_field_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_from_disk
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_hint_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_hint_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_pasted
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_review_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_review_entry_hop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_review_new
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_review_relay
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_review_relay_entry
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_save
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_use
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_qr_warning
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_scan_qr
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ImportReview(
    pending: PendingImport,
    activeName: String,
    canSaveOnly: Boolean,
    onUse: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = VpnChainTheme.colors
    Column {
        Text(
            when (pending) {
                is PendingImport.Conf -> {
                    val endpoint = pending.entry.endpoint()
                    if (pending.intoActive) {
                        stringResource(Res.string.settings_import_review_entry_hop, activeName, endpoint)
                    } else {
                        stringResource(Res.string.settings_import_review_new, pending.entry.endpointHost, endpoint)
                    }
                }
                is PendingImport.SecretsEnv -> pending.entryHop?.let {
                    stringResource(Res.string.settings_import_review_relay_entry, pending.relay.vpsIp, it.endpoint())
                } ?: stringResource(Res.string.settings_import_review_relay, pending.relay.vpsIp)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(Res.string.settings_import_review_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = stringResource(Res.string.settings_import_use),
                onClick = onUse,
                compact = true,
                tone = ActionTone.Primary,
            )
            if (canSaveOnly) {
                ActionButton(text = stringResource(Res.string.settings_import_save), onClick = onSave, compact = true)
            }
            ActionButton(text = stringResource(Res.string.settings_import_cancel), onClick = onCancel, compact = true)
        }
    }
}

internal fun WireGuardEntry.endpoint() = "$endpointHost:$endpointPort"

@Composable
internal fun ImportSection(
    clearKey: Int,
    onImportText: (String) -> Unit,
    onImportAsEntryHop: ((String) -> Unit)?,
    onImportDefault: () -> Unit,
    onScanQr: () -> Unit,
) {
    val colors = VpnChainTheme.colors
    var pasted by remember(clearKey) { mutableStateOf("") }

    Column {
        // Typing 12 secret fields on a phone keyboard is miserable — scanning the
        // desktop app's QR is the path Android users actually want.
        if (currentPlatform == Platform.Android) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.settings_scan_qr),
                onClick = onScanQr,
                tone = ActionTone.Primary,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            if (currentPlatform == Platform.Android) {
                stringResource(Res.string.settings_import_hint_android)
            } else {
                stringResource(Res.string.settings_import_hint_desktop)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        ValueField(
            label = stringResource(Res.string.settings_import_field_label),
            value = pasted,
            minLines = 4,
            sensitive = true,
            onValueChange = { pasted = it },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.settings_import_pasted),
                onClick = { onImportText(pasted) },
                enabled = pasted.isNotBlank(),
                tone = if (currentPlatform == Platform.Desktop) ActionTone.Primary else ActionTone.Outlined,
            )
            if (onImportAsEntryHop != null && WireGuardConfParser.looksLikeConf(pasted)) {
                ActionButton(
                    text = stringResource(Res.string.settings_import_as_entry_hop),
                    onClick = { onImportAsEntryHop(pasted) },
                    compact = true,
                )
            }
            if (currentPlatform == Platform.Desktop) {
                ActionButton(text = stringResource(Res.string.settings_import_from_disk), onClick = onImportDefault, compact = true)
            }
        }
    }
}

/** Renders a pairing payload as a scannable QR, the desktop side of device pairing. */
@Composable
internal fun QrPairingPanel(payload: String) {
    val colors = VpnChainTheme.colors
    Column {
        Text(
            stringResource(Res.string.settings_qr_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.reroutedText,
        )
        Spacer(Modifier.height(12.dp))
        QrImage(payload)
    }
}
