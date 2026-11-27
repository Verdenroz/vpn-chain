package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_clear
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
    onSave: (ChainProfile) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    val edited = ChainProfile(
                        vpsIp = vpsIp.trim(),
                        vlessUuid = uuid.trim(),
                        realityPublicKey = pubKey.trim(),
                        shortId = shortId.trim(),
                        sni = sni.trim().ifBlank { ChainProfile.DEFAULT_SNI },
                        serverPort = port.toIntOrNull() ?: ChainProfile.DEFAULT_SERVER_PORT,
                        localProxyPort = proxyPort.toIntOrNull() ?: ChainProfile.DEFAULT_LOCAL_PROXY_PORT,
                    )
                    onSave(edited.keepingPairing(initial?.relay))
                },
            )
            ActionButton(text = stringResource(Res.string.settings_clear), onClick = onClear, compact = true)
        }
    }
}

/** The form does not show how this device was paired, so a save keeps it while it points at the same relay. */
internal fun ChainProfile.keepingPairing(before: ChainProfile?): ChainProfile {
    val sameRelay = before != null && before.vpsIp == vpsIp && before.realityPublicKey == realityPublicKey
    return copy(
        managementKeyId = before?.managementKeyId.takeIf { sameRelay },
        managementAdmin = before?.managementAdmin == true && sameRelay,
        managementDevice = before?.managementDevice.takeIf { sameRelay },
    )
}
