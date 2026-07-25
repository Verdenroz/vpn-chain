package com.verdenroz.vpnchain.feature.chain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.RouteDisplay
import com.verdenroz.vpnchain.core.designsystem.component.RoutePoint
import com.verdenroz.vpnchain.core.designsystem.component.StatusIndicator
import com.verdenroz.vpnchain.core.designsystem.component.ThrowSwitch
import com.verdenroz.vpnchain.core.designsystem.component.VpnChainLogo
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelColors
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.domain.ChainHop
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.HopEvidence
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.ui.SectionLabel
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.chain.generated.resources.Res
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_app_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_empty_state_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_configured
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_measured
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_evidence_recalled
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_entry
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_exit
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_origin
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_hop_unresolved
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_helper
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_hint_proton
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_none
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_kill_switch_protected
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_open_settings
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_path_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_route_attribution
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_route_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_kill_switch
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_mode
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_placeholder
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_rtt
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_row_rtt_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChainRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChainViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChainScreen(
        uiState = uiState,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
fun ChainScreen(
    uiState: ChainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val connected = uiState.status.state == TunnelState.Connected

    Column(
        modifier
            .fillMaxSize()
            .background(colors.shellDeep)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Nameplate(uiState.status)

        Spacer(Modifier.height(18.dp))

        // One enclosure fills the housing, with the sections engraved onto it
        // rather than stacked as separate cards. The control stays pinned to the
        // bottom edge where a hand expects it, whatever the readouts do above.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .machinedSurface(colors, corner = 4.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel(stringResource(Res.string.chain_route_title))
                Spacer(Modifier.height(10.dp))
                RouteDisplay(points = uiState.route.toPoints(colors), live = connected)
                Spacer(Modifier.height(7.dp))
                Text(
                    stringResource(Res.string.chain_route_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )

                Spacer(Modifier.height(22.dp))

                SectionLabel(stringResource(Res.string.chain_path_title))
                Spacer(Modifier.height(12.dp))
                HopRail(hops = uiState.route.hops)
            }

            // Sits with the control rather than the readouts above: these three
            // are what you check as you reach for the switch.
            Spacer(Modifier.height(16.dp))
            StatusStrip(status = uiState.status, rttMs = uiState.route.throughRttMs)
            Spacer(Modifier.height(14.dp))

            if (uiState.hasProfile) {
                ThrowSwitch(
                    on = connected || uiState.status.state == TunnelState.Connecting,
                    enabled = uiState.status.state != TunnelState.Connecting,
                    onToggle = { wantsOn -> if (wantsOn) onConnect() else onDisconnect() },
                )
            } else {
                EmptyProfileNotice(onOpenSettings)
            }
        }
    }
}

/** The device badge: maker's mark struck into the enclosure, annunciator beside it. */
@Composable
private fun Nameplate(status: ChainStatus) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VpnChainLogo(
            size = 40.dp,
            contentDescription = stringResource(Res.string.chain_app_title),
        )
        StatusIndicator(status.state)
    }
}

/**
 * The chain traced hop by hop, in the order traffic travels, with a rail
 * running between the lamps so the path reads as one circuit. Each row states
 * how its address was learned — a configured hop never masquerades as measured.
 */
@Composable
private fun HopRail(hops: List<ChainHop>) {
    val colors = PanelTheme.colors
    if (hops.isEmpty()) {
        Text(
            stringResource(Res.string.chain_hop_unresolved),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        return
    }
    Column {
        hops.forEachIndexed { index, hop ->
            // The leg is drawn before the hop it feeds, so the rail reads in the
            // order traffic travels: hop, what carries it onward, next hop.
            hop.via?.takeIf { index > 0 }?.let { via ->
                LegRow(via = via, lamp = hop.role.lampColor(colors), carrying = hop.carrying)
            }
            HopRow(
                hop = hop,
                lamp = hop.role.lampColor(colors),
                isFirst = index == 0,
                isLast = index == hops.lastIndex,
            )
        }
    }
}

/** The transport for one leg, captioned on the rail between two lamps. */
@Composable
private fun LegRow(via: String, lamp: Color, carrying: Boolean) {
    val colors = PanelTheme.colors
    val railColor = if (carrying) lamp.copy(alpha = 0.45f) else colors.edgeHighlight.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth().height(LegHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(LampColumnWidth)
                .height(LegHeight)
                .drawBehind {
                    val cx = size.width / 2f
                    drawLine(railColor, Offset(cx, 0f), Offset(cx, size.height), 1.5f)
                },
        )
        Text(
            via,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun HopRow(hop: ChainHop, lamp: Color, isFirst: Boolean, isLast: Boolean) {
    val colors = PanelTheme.colors
    val railColor = if (hop.carrying) lamp.copy(alpha = 0.45f) else colors.edgeHighlight.copy(alpha = 0.5f)

    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(LampColumnWidth)
                .height(RowHeight)
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val gap = LampGapPx
                    if (!isFirst) drawLine(railColor, Offset(cx, 0f), Offset(cx, cy - gap), 1.5f)
                    if (!isLast) drawLine(railColor, Offset(cx, cy + gap), Offset(cx, size.height), 1.5f)
                },
            contentAlignment = Alignment.Center,
        ) {
            IndicatorLamp(color = lamp, lit = hop.carrying, size = 9.dp)
        }

        Text(
            stringResource(hop.role.labelRes()).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.engraved,
            modifier = Modifier.width(RoleColumnWidth),
        )

        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                hop.location?.countryName ?: stringResource(Res.string.chain_hop_unresolved),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hop.carrying) colors.readout else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hop.ip?.let { ip ->
                Text(
                    ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Fixed width, or a long address pushes the provenance tag on top of it.
        hop.evidence.labelRes()?.let { evidenceRes ->
            Text(
                stringResource(evidenceRes),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(EvidenceColumnWidth),
            )
        }
    }
}

/** Three readouts across the panel: protection, latency, and what mode is live. */
@Composable
private fun StatusStrip(status: ChainStatus, rttMs: Int?) {
    val colors = PanelTheme.colors
    val connected = status.state == TunnelState.Connected
    val protectedNow = status.killSwitch == KillSwitchState.Active

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.shellDeep)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            // Weighted rather than space-between: three readouts sharing a row
            // will otherwise overlap once the panel is narrow.
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Readout(label = stringResource(Res.string.chain_row_kill_switch), modifier = Modifier.weight(1.1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IndicatorLamp(
                        color = if (protectedNow) colors.lampGreen else colors.lampRed,
                        lit = connected,
                        size = 8.dp,
                    )
                    Text(
                        stringResource(
                            if (protectedNow) Res.string.chain_kill_switch_protected
                            else Res.string.chain_kill_switch_none,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !connected -> colors.muted
                            protectedNow -> colors.lampGreen
                            else -> colors.lampRed
                        },
                    )
                }
            }

            Readout(
                label = stringResource(Res.string.chain_row_rtt),
                modifier = Modifier.weight(0.8f),
            ) {
                Text(
                    rttMs?.let { stringResource(Res.string.chain_row_rtt_value, it) }
                        ?: stringResource(Res.string.chain_row_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rttMs != null) colors.readout else colors.muted,
                    maxLines = 1,
                )
            }

            Readout(
                label = stringResource(Res.string.chain_row_mode),
                alignEnd = true,
                modifier = Modifier.weight(1.4f),
            ) {
                Text(
                    status.detail?.asString() ?: stringResource(Res.string.chain_row_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.state == TunnelState.Error) colors.lampRed else colors.muted,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AnimatedVisibility(
            visible = connected && !protectedNow,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            // The remedy differs per cause, and pointing at Settings when the
            // setting is already on just sends the user in a circle.
            Text(
                stringResource(
                    when (status.killSwitch) {
                        KillSwitchState.HelperUnavailable -> Res.string.chain_kill_switch_hint_helper
                        KillSwitchState.ProtonAppRequired -> Res.string.chain_kill_switch_hint_proton
                        else -> Res.string.chain_kill_switch_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.lampRed,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun Readout(
    label: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    value: @Composable () -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PanelTheme.colors.engraved,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        value()
    }
}

@Composable
private fun EmptyProfileNotice(onOpenSettings: () -> Unit) {
    Column {
        Text(
            stringResource(Res.string.chain_empty_state_title),
            style = MaterialTheme.typography.bodyMedium,
            color = PanelTheme.colors.muted,
        )
        Spacer(Modifier.height(14.dp))
        PanelButton(
            text = stringResource(Res.string.chain_open_settings),
            onClick = onOpenSettings,
            prominent = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ChainRoute.toPoints(colors: PanelColors): List<RoutePoint> = hops.mapNotNull { hop ->
    val location = hop.location ?: return@mapNotNull null
    RoutePoint(
        label = location.countryName,
        lat = location.lat,
        lon = location.lon,
        lamp = hop.role.lampColor(colors),
        carrying = hop.carrying,
    )
}

// Each role gets its own lamp type, so a hop's identity survives being reduced
// to a dot on the display.
private fun HopRole.lampColor(colors: PanelColors): Color = when (this) {
    HopRole.Origin -> colors.lampWhite
    HopRole.Entry -> colors.lampAmber
    HopRole.Exit -> colors.lampGreen
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

private val RowHeight = 46.dp
private val LegHeight = 26.dp
private val EvidenceColumnWidth = 66.dp
private val LampColumnWidth = 30.dp
private val RoleColumnWidth = 74.dp
private const val LampGapPx = 9f
