package com.verdenroz.vpnchain.feature.chain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.InfoCard
import com.verdenroz.vpnchain.core.designsystem.component.MapNode
import com.verdenroz.vpnchain.core.designsystem.component.NodeKind
import com.verdenroz.vpnchain.core.designsystem.component.RouteMap
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
    val nodes = remember(uiState.route) { uiState.route.toMapNodes() }
    val connected = uiState.status.state == TunnelState.Connected

    Column(modifier.fillMaxSize().background(VpnChainTheme.colors.background)) {
        RouteMap(nodes, connected, Modifier.weight(1f).fillMaxWidth())
        InfoCard(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()) {
            ChainDetails(uiState, onConnect, onDisconnect, onOpenSettings)
        }
    }
}

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
