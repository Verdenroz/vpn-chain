package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.verdenroz.vpnchain.core.common.Platform
import com.verdenroz.vpnchain.core.common.currentPlatform
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.PanelField
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ProtonWireGuardEntry
import com.verdenroz.vpnchain.core.ui.SectionLabel
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_clear
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_detail_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_detail_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_title_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_title_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_local_proxy_port
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_reality_public_key
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_server_port
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_short_id
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_sni
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_vless_uuid
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_vps_ip
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_address
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_dns
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_endpoint_host
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_endpoint_port
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_peer_public_key
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_wg_private_key
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_save
import org.jetbrains.compose.resources.stringResource

/** Editable chain identity; credential fields are masked. */
@Composable
fun ProfileForm(
    initial: ChainProfile?,
    onSave: (ChainProfile) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    var vpsIp by remember(initial) { mutableStateOf(initial?.vpsIp ?: "") }
    var uuid by remember(initial) { mutableStateOf(initial?.vlessUuid ?: "") }
    var pubKey by remember(initial) { mutableStateOf(initial?.realityPublicKey ?: "") }
    var shortId by remember(initial) { mutableStateOf(initial?.shortId ?: "") }
    var sni by remember(initial) { mutableStateOf(initial?.sni ?: ChainProfile.DEFAULT_SNI) }
    var port by remember(initial) {
        mutableStateOf((initial?.serverPort ?: ChainProfile.DEFAULT_SERVER_PORT).toString())
    }
    var proxyPort by remember(initial) {
        mutableStateOf((initial?.localProxyPort ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT).toString())
    }
    // Optional TUN-mode entry hop (Proton WireGuard), used on Android and on
    // desktop when system-wide TUN is on. Empty = relay-only.
    var wgPrivKey by remember(initial) { mutableStateOf(initial?.protonEntry?.privateKey ?: "") }
    var wgAddress by remember(initial) { mutableStateOf(initial?.protonEntry?.address ?: "") }
    var wgPeerKey by remember(initial) { mutableStateOf(initial?.protonEntry?.peerPublicKey ?: "") }
    var wgHost by remember(initial) { mutableStateOf(initial?.protonEntry?.endpointHost ?: "") }
    var wgPort by remember(initial) {
        mutableStateOf(
            (initial?.protonEntry?.endpointPort ?: ProtonWireGuardEntry.DEFAULT_ENDPOINT_PORT).toString(),
        )
    }
    var wgDns by remember(initial) { mutableStateOf(initial?.protonEntry?.dns ?: "") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelField(stringResource(Res.string.settings_field_vps_ip), vpsIp) { vpsIp = it }
        PanelField(stringResource(Res.string.settings_field_vless_uuid), uuid, masked = true) { uuid = it }
        PanelField(
            stringResource(Res.string.settings_field_reality_public_key),
            pubKey,
            masked = true,
        ) { pubKey = it }
        PanelField(stringResource(Res.string.settings_field_short_id), shortId, masked = true) { shortId = it }
        PanelField(stringResource(Res.string.settings_field_sni), sni) { sni = it }
        PanelField(stringResource(Res.string.settings_field_server_port), port, numeric = true) { port = it }
        PanelField(
            stringResource(Res.string.settings_field_local_proxy_port),
            proxyPort,
            numeric = true,
        ) { proxyPort = it }

        Spacer(Modifier.height(4.dp))
        SectionLabel(
            if (currentPlatform == Platform.Android) {
                stringResource(Res.string.settings_entry_hop_title_android)
            } else {
                stringResource(Res.string.settings_entry_hop_title_desktop)
            },
        )
        Text(
            if (currentPlatform == Platform.Android) {
                stringResource(Res.string.settings_entry_hop_detail_android)
            } else {
                stringResource(Res.string.settings_entry_hop_detail_desktop)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )

        PanelField(stringResource(Res.string.settings_field_wg_private_key), wgPrivKey, masked = true) {
            wgPrivKey = it
        }
        PanelField(stringResource(Res.string.settings_field_wg_address), wgAddress) { wgAddress = it }
        PanelField(stringResource(Res.string.settings_field_wg_peer_public_key), wgPeerKey, masked = true) {
            wgPeerKey = it
        }
        PanelField(stringResource(Res.string.settings_field_wg_endpoint_host), wgHost) { wgHost = it }
        PanelField(
            stringResource(Res.string.settings_field_wg_endpoint_port),
            wgPort,
            numeric = true,
        ) { wgPort = it }
        PanelField(stringResource(Res.string.settings_field_wg_dns), wgDns) { wgDns = it }

        val valid = vpsIp.isNotBlank() && uuid.isNotBlank() &&
            pubKey.isNotBlank() && shortId.isNotBlank()

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(
                text = stringResource(Res.string.settings_save),
                enabled = valid,
                prominent = true,
                onClick = {
                    val entry = if (
                        wgPrivKey.isNotBlank() && wgAddress.isNotBlank() &&
                        wgPeerKey.isNotBlank() && wgHost.isNotBlank()
                    ) {
                        ProtonWireGuardEntry(
                            privateKey = wgPrivKey.trim(),
                            address = wgAddress.trim(),
                            peerPublicKey = wgPeerKey.trim(),
                            endpointHost = wgHost.trim(),
                            endpointPort = wgPort.toIntOrNull()
                                ?: ProtonWireGuardEntry.DEFAULT_ENDPOINT_PORT,
                            dns = wgDns.trim().ifBlank { null },
                        )
                    } else {
                        null
                    }
                    onSave(
                        ChainProfile(
                            vpsIp = vpsIp.trim(),
                            vlessUuid = uuid.trim(),
                            realityPublicKey = pubKey.trim(),
                            shortId = shortId.trim(),
                            sni = sni.trim().ifBlank { ChainProfile.DEFAULT_SNI },
                            serverPort = port.toIntOrNull() ?: ChainProfile.DEFAULT_SERVER_PORT,
                            localProxyPort = proxyPort.toIntOrNull()
                                ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT,
                            protonEntry = entry,
                        ),
                    )
                },
            )
            PanelButton(text = stringResource(Res.string.settings_clear), onClick = onClear)
        }
    }
}
