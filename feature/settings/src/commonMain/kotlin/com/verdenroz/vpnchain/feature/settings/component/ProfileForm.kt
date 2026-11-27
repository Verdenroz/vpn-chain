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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.common.Platform
import com.verdenroz.vpnchain.core.common.currentPlatform
import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.ValueField
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.core.ui.SectionLabel
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_clear
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_conf_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_current
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_detail_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_detail_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_replace_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_title_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_entry_hop_title_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_local_proxy_port
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_reality_public_key
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_server_port
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_short_id
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_sni
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_vless_uuid
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_field_vps_ip
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_save
import org.jetbrains.compose.resources.stringResource

/** Editable chain identity; credential fields are masked. */
@Composable
fun ProfileForm(
    initial: SavedProfile?,
    onSave: (ChainProfile, WireGuardEntry?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VpnChainTheme.colors
    var vpsIp by remember(initial) { mutableStateOf(initial?.relay?.vpsIp ?: "") }
    var uuid by remember(initial) { mutableStateOf(initial?.relay?.vlessUuid ?: "") }
    var pubKey by remember(initial) { mutableStateOf(initial?.relay?.realityPublicKey ?: "") }
    var shortId by remember(initial) { mutableStateOf(initial?.relay?.shortId ?: "") }
    var sni by remember(initial) { mutableStateOf(initial?.relay?.sni ?: ChainProfile.DEFAULT_SNI) }
    var port by remember(initial) {
        mutableStateOf((initial?.relay?.serverPort ?: ChainProfile.DEFAULT_SERVER_PORT).toString())
    }
    var proxyPort by remember(initial) {
        mutableStateOf((initial?.relay?.localProxyPort ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT).toString())
    }
    var entry by remember(initial) { mutableStateOf(initial?.wireGuard) }
    var conf by remember(initial) { mutableStateOf("") }
    var confError by remember(initial) { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValueField(stringResource(Res.string.settings_field_vps_ip), vpsIp) { vpsIp = it }
        ValueField(stringResource(Res.string.settings_field_vless_uuid), uuid, masked = true) { uuid = it }
        ValueField(
            stringResource(Res.string.settings_field_reality_public_key),
            pubKey,
            masked = true,
        ) { pubKey = it }
        ValueField(stringResource(Res.string.settings_field_short_id), shortId, masked = true) { shortId = it }
        ValueField(stringResource(Res.string.settings_field_sni), sni) { sni = it }
        ValueField(stringResource(Res.string.settings_field_server_port), port, numeric = true) { port = it }
        ValueField(
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
            color = colors.inkMuted,
        )

        entry?.let { current ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.settings_entry_hop_current, current.provider ?: "WireGuard", current.endpointHost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(compact = true, text = stringResource(Res.string.settings_entry_hop_remove), onClick = { entry = null })
            }
        }
        ValueField(
            label = stringResource(if (entry == null) Res.string.settings_entry_hop_conf_label else Res.string.settings_entry_hop_replace_label),
            value = conf,
            sensitive = true,
            minLines = 5,
            onValueChange = { conf = it; confError = null },
        )
        confError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.unprotectedText)
        }

        val valid = vpsIp.isNotBlank() && uuid.isNotBlank() &&
            pubKey.isNotBlank() && shortId.isNotBlank()

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                compact = true,
                text = stringResource(Res.string.settings_save),
                enabled = valid,
                tone = ActionTone.Primary,
                onClick = {
                    val pasted = conf.trim()
                    val saved = if (pasted.isEmpty()) {
                        entry
                    } else {
                        WireGuardConfParser.parse(pasted).getOrElse {
                            confError = it.message
                            return@ActionButton
                        }
                    }
                    val edited = ChainProfile(
                        vpsIp = vpsIp.trim(),
                        vlessUuid = uuid.trim(),
                        realityPublicKey = pubKey.trim(),
                        shortId = shortId.trim(),
                        sni = sni.trim().ifBlank { ChainProfile.DEFAULT_SNI },
                        serverPort = port.toIntOrNull() ?: ChainProfile.DEFAULT_SERVER_PORT,
                        localProxyPort = proxyPort.toIntOrNull() ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT,
                    )
                    onSave(edited.keepingPairing(initial?.relay), saved)
                },
            )
            ActionButton(text = stringResource(Res.string.settings_clear), onClick = onClear, compact = true)
        }
    }
}

/** The form does not show how this device was paired, so a save keeps it. */
internal fun ChainProfile.keepingPairing(before: ChainProfile?) = copy(
    managementKeyId = before?.managementKeyId,
    managementAdmin = before?.managementAdmin ?: false,
    managementDevice = before?.managementDevice,
)
