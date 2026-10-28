package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.domain.PairingStep
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.PairingStatus
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_connecting
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_dismiss
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_done
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_done_member
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_entry_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_reconnecting
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_registering
import org.jetbrains.compose.resources.stringResource

@Composable
fun PairingStatusPanel(status: PairingStatus, onDismiss: () -> Unit) {
    val colors = VpnChainTheme.colors
    Column {
        SelectionContainer {
            when (status) {
                is PairingStatus.Working -> Text(
                    when (status.step) {
                        PairingStep.Connecting -> stringResource(Res.string.settings_pairing_connecting, status.host)
                        PairingStep.Registering -> stringResource(Res.string.settings_pairing_registering)
                        PairingStep.Reconnecting -> stringResource(Res.string.settings_pairing_reconnecting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                )
                is PairingStatus.Done -> Column {
                    Text(
                        stringResource(if (status.admin) Res.string.settings_pairing_done else Res.string.settings_pairing_done_member, status.host),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.connectedText,
                    )
                    if (status.entryHopFailed) {
                        Text(
                            stringResource(Res.string.settings_pairing_entry_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink,
                        )
                    }
                }
                is PairingStatus.Failed -> Text(
                    status.text.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.unprotectedText,
                )
            }
        }
        if (status !is PairingStatus.Working) {
            Spacer(Modifier.height(12.dp))
            ActionButton(text = stringResource(Res.string.settings_pairing_dismiss), onClick = onDismiss, compact = true)
        }
    }
}
