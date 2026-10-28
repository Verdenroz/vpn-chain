package com.verdenroz.vpnchain.feature.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.common.formatDuration
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.InfoRow
import com.verdenroz.vpnchain.core.designsystem.component.RowMarker
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.domain.ChainHop
import com.verdenroz.vpnchain.core.domain.HopEvidence
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.chain.generated.resources.Res
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_change
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_connect
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_cut_off
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_disconnect
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_empty_state_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_configured
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_measured
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_recalled
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_entry
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_exit
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_origin
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_unresolved
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_external_app
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_helper_failed
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_helper_not_found
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_helper_timed_out
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_helper_unprivileged
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_no_exempt
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_not_engaged
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_none
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_protected
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_open_settings
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_pair_again
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_remove_connection
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_ip
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_kill_switch
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_placeholder
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_state_connected
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_state_connected_rtt
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_state_connecting
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_state_disconnected
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_state_error
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_uptime
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Everything the card and the desktop side panel say, plus the two buttons. */
@Composable
internal fun ChainDetails(
    uiState: ChainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onPairAgain: () -> Unit,
    onRemove: () -> Unit,
    fillHeight: Boolean = false,
) {
    Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = fillHeight).verticalScroll(rememberScrollState())) {
            Readouts(uiState)
        }
        Spacer(Modifier.height(16.dp))
        if (uiState.cutOff) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    stringResource(Res.string.chain_remove_connection),
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    stringResource(Res.string.chain_pair_again),
                    onClick = onPairAgain,
                    modifier = Modifier.weight(1f),
                    tone = ActionTone.Primary,
                )
            }
        } else if (uiState.hasProfile) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    stringResource(Res.string.chain_change),
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
                PrimaryAction(uiState.status.state, onConnect, onDisconnect, Modifier.weight(1f))
            }
        } else {
            Text(
                stringResource(Res.string.chain_empty_state_title),
                style = MaterialTheme.typography.bodyMedium,
                color = VpnChainTheme.colors.inkMuted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ActionButton(
                stringResource(Res.string.chain_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Readouts(uiState: ChainUiState) {
    val status = uiState.status
    val connected = status.state == TunnelState.Connected
    val exit = uiState.route.hop(HopRole.Exit)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StateLine(status.state, uiState.route.throughRttMs)
        Text(
            exit?.location?.countryName ?: stringResource(Res.string.chain_hop_unresolved),
            style = MaterialTheme.typography.headlineMedium,
            color = VpnChainTheme.colors.ink,
        )
        val since = uiState.connectedSinceMillis
        if (connected && since != null) UptimeText(since)
    }
    Notices(status, connected, uiState.cutOff)

    Spacer(Modifier.height(12.dp))
    uiState.route.hops.forEach { hop -> HopInfoRow(hop) }
    InfoRow(
        label = stringResource(Res.string.chain_row_ip),
        value = exit?.ip ?: stringResource(Res.string.chain_row_placeholder),
    )
    KillSwitchRow(status)
}

@Composable
private fun StateLine(state: TunnelState, rttMs: Int?) {
    val colors = VpnChainTheme.colors
    val connected = state == TunnelState.Connected
    Text(
        when {
            connected && rttMs != null -> stringResource(Res.string.chain_state_connected_rtt, rttMs)
            else -> stringResource(state.labelRes())
        },
        style = MaterialTheme.typography.titleSmall,
        color = when (state) {
            TunnelState.Connected -> colors.connectedText
            TunnelState.Connecting -> colors.inkMuted
            TunnelState.Disconnected, TunnelState.Error -> colors.unprotectedText
        },
    )
}

@Composable
private fun PrimaryAction(
    state: TunnelState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier,
) {
    when (state) {
        TunnelState.Connected -> ActionButton(
            stringResource(Res.string.chain_disconnect),
            onClick = onDisconnect,
            modifier = modifier,
            tone = ActionTone.Danger,
        )
        TunnelState.Connecting -> ActionButton(
            stringResource(Res.string.chain_state_connecting),
            onClick = {},
            modifier = modifier,
            tone = ActionTone.Primary,
            enabled = false,
        )
        TunnelState.Disconnected, TunnelState.Error -> ActionButton(
            stringResource(Res.string.chain_connect),
            onClick = onConnect,
            modifier = modifier,
            tone = ActionTone.Primary,
        )
    }
}

@Composable
private fun HopInfoRow(hop: ChainHop) {
    val colors = VpnChainTheme.colors
    InfoRow(
        label = stringResource(hop.role.labelRes()),
        value = hop.location?.let { hopRowValue(it, hop.provider) } ?: stringResource(Res.string.chain_hop_unresolved),
        marker = when (hop.role) {
            HopRole.Exit -> RowMarker(if (hop.carrying) colors.route else colors.idleRoute, filled = true)
            HopRole.Origin, HopRole.Entry -> RowMarker(colors.ring, filled = false)
        },
        note = hop.evidence.labelRes()?.let { stringResource(it) },
    )
}

@Composable
private fun KillSwitchRow(status: ChainStatus) {
    InfoRow(
        label = stringResource(Res.string.chain_row_kill_switch),
        value = stringResource(
            if (status.killSwitch == KillSwitchState.Active) Res.string.chain_kill_switch_protected
            else Res.string.chain_kill_switch_none,
        ),
    )
}

/** Error and kill-switch reasons carry commands to run, so they stay selectable. */
@Composable
private fun Notices(status: ChainStatus, connected: Boolean, cutOff: Boolean) {
    val colors = VpnChainTheme.colors
    val detail = if (cutOff) stringResource(Res.string.chain_cut_off) else status.detail?.asString()
    val hint = if (connected && status.killSwitch != KillSwitchState.Active) {
        stringResource(
            when (status.killSwitch) {
                KillSwitchState.HelperNotFound -> Res.string.chain_kill_switch_hint_helper_not_found
                KillSwitchState.HelperUnprivileged -> Res.string.chain_kill_switch_hint_helper_unprivileged
                KillSwitchState.HelperFailed -> Res.string.chain_kill_switch_hint_helper_failed
                KillSwitchState.HelperTimedOut -> Res.string.chain_kill_switch_hint_helper_timed_out
                KillSwitchState.NoExemptAddresses -> Res.string.chain_kill_switch_hint_no_exempt
                KillSwitchState.NotEngaged -> Res.string.chain_kill_switch_hint_not_engaged
                KillSwitchState.ExternalAppRequired -> Res.string.chain_kill_switch_hint_external_app
                else -> Res.string.chain_kill_switch_hint
            },
        )
    } else {
        null
    }
    if (detail == null && hint == null) return
    SelectionContainer {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            detail?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.state == TunnelState.Error) colors.unprotectedText else colors.inkMuted,
                )
            }
            hint?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall, color = colors.unprotectedText) }
        }
    }
}

/** Owns its one-second tick so only this line recomposes as it counts. */
@Composable
private fun UptimeText(sinceMillis: Long) {
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(sinceMillis) {
        while (true) {
            nowMillis = currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        stringResource(Res.string.chain_uptime, formatDuration(nowMillis - sinceMillis)),
        style = MaterialTheme.typography.bodySmall,
        color = VpnChainTheme.colors.inkMuted,
    )
}

private fun TunnelState.labelRes(): StringResource = when (this) {
    TunnelState.Connected -> Res.string.chain_state_connected
    TunnelState.Connecting -> Res.string.chain_state_connecting
    TunnelState.Disconnected -> Res.string.chain_state_disconnected
    TunnelState.Error -> Res.string.chain_state_error
}

private fun HopRole.labelRes(): StringResource = when (this) {
    HopRole.Origin -> Res.string.chain_hop_origin
    HopRole.Entry -> Res.string.chain_hop_entry
    HopRole.Exit -> Res.string.chain_hop_exit
}

private fun HopEvidence.labelRes(): StringResource? = when (this) {
    HopEvidence.Measured -> Res.string.chain_evidence_measured
    HopEvidence.Configured -> Res.string.chain_evidence_configured
    HopEvidence.Recalled -> Res.string.chain_evidence_recalled
    HopEvidence.Unknown -> null
}
