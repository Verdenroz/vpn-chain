package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.verdenroz.vpnchain.app.generated.resources.Res
import com.verdenroz.vpnchain.app.generated.resources.status_connected
import com.verdenroz.vpnchain.app.generated.resources.status_connecting
import com.verdenroz.vpnchain.app.generated.resources.status_unprotected
import com.verdenroz.vpnchain.core.designsystem.component.StatusBar
import com.verdenroz.vpnchain.core.designsystem.component.StatusTone
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.ui.shouldUseDarkTheme
import com.verdenroz.vpnchain.feature.chain.navigation.CHAIN_ROUTE
import com.verdenroz.vpnchain.feature.chain.navigation.chainScreen
import com.verdenroz.vpnchain.feature.logs.navigation.logsScreen
import com.verdenroz.vpnchain.feature.settings.QrScanRequest
import com.verdenroz.vpnchain.feature.settings.navigation.settingsScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VpnChainApp(appViewModel: AppViewModel = koinViewModel()) {
    val themeConfig by appViewModel.themeConfig.collectAsStateWithLifecycle()
    val tunnelState by appViewModel.tunnelState.collectAsStateWithLifecycle()

    VpnChainTheme(darkTheme = shouldUseDarkTheme(themeConfig)) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination

        // Consumed by SettingsRoute once it's on screen; here we only make sure
        // it gets on screen.
        val scanRequested by QrScanRequest.pending.collectAsStateWithLifecycle()
        LaunchedEffect(scanRequested) {
            if (scanRequested) navController.switchTo(TopLevelDestination.SETTINGS)
        }

        Column(Modifier.fillMaxSize().background(VpnChainTheme.colors.background)) {
            StatusBar(
                tone = tunnelState.tone(),
                label = stringResource(tunnelState.labelRes()),
            )
            NavHost(
                navController = navController,
                startDestination = CHAIN_ROUTE,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                chainScreen(onOpenSettings = { navController.switchTo(TopLevelDestination.SETTINGS) })
                logsScreen()
                settingsScreen()
            }
            BottomTabs(
                isSelected = { destination ->
                    currentDestination?.hierarchy?.any { it.route == destination.route } == true
                },
                onSelect = { destination -> navController.switchTo(destination) },
            )
        }
    }
}

private fun NavHostController.switchTo(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(CHAIN_ROUTE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun TunnelState.tone() = when (this) {
    TunnelState.Connected -> StatusTone.Connected
    TunnelState.Connecting -> StatusTone.Connecting
    TunnelState.Disconnected, TunnelState.Error -> StatusTone.Unprotected
}

private fun TunnelState.labelRes() = when (this) {
    TunnelState.Connected -> Res.string.status_connected
    TunnelState.Connecting -> Res.string.status_connecting
    TunnelState.Disconnected, TunnelState.Error -> Res.string.status_unprotected
}
