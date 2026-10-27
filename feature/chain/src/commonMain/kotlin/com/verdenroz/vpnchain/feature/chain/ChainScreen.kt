package com.verdenroz.vpnchain.feature.chain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.Edge
import com.verdenroz.vpnchain.core.designsystem.component.InfoCard
import com.verdenroz.vpnchain.core.designsystem.component.MapNode
import com.verdenroz.vpnchain.core.designsystem.component.NodeKind
import com.verdenroz.vpnchain.core.designsystem.component.RouteMap
import com.verdenroz.vpnchain.core.designsystem.component.hairline
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.model.TunnelState
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
        onUseMyConnection = viewModel::sendDirect,
        onDismissBlocked = viewModel::dismissBlocked,
        modifier = modifier,
    )
}

@Composable
fun ChainScreen(
    uiState: ChainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseMyConnection: (String) -> Unit,
    onDismissBlocked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(uiState.route) { uiState.route.toMapNodes() }
    val connected = uiState.status.state == TunnelState.Connected

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= SIDE_PANEL_BREAKPOINT) {
            Row(Modifier.fillMaxSize()) {
                RouteMap(nodes, connected, Modifier.weight(1f).fillMaxHeight())
                SidePanel {
                    uiState.blockedSite?.let { site ->
                        BlockedSitePrompt(site, onDismissBlocked, onUseMyConnection, Modifier.padding(bottom = 16.dp))
                    }
                    ChainDetails(uiState, onConnect, onDisconnect, onOpenSettings, fillHeight = true)
                }
            }
        } else {
            val cardMaxHeight = maxHeight - MIN_MAP_HEIGHT
            Column(Modifier.fillMaxSize()) {
                RouteMap(nodes, connected, Modifier.weight(1f).fillMaxWidth())
                uiState.blockedSite?.let { site ->
                    BlockedSitePrompt(
                        site,
                        onDismissBlocked,
                        onUseMyConnection,
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    )
                }
                InfoCard(
                    Modifier
                        .heightIn(max = cardMaxHeight)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .fillMaxWidth(),
                ) {
                    ChainDetails(uiState, onConnect, onDisconnect, onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun SidePanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = VpnChainTheme.colors
    Column(
        Modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .hairline(Edge.Left, colors.line)
            .padding(24.dp),
        content = content,
    )
}

private val SIDE_PANEL_BREAKPOINT = 600.dp
private val MIN_MAP_HEIGHT = 120.dp

private fun ChainRoute.toMapNodes(): List<MapNode> = hops.mapNotNull { hop ->
    val location = hop.location ?: return@mapNotNull null
    MapNode(
        label = location.countryName,
        lat = location.lat,
        lon = location.lon,
        kind = when (hop.role) {
            HopRole.Origin -> NodeKind.Origin
            HopRole.Entry -> NodeKind.Entry
            HopRole.Exit -> NodeKind.Exit
        },
    )
}
