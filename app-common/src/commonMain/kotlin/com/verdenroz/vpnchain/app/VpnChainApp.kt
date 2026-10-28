package com.verdenroz.vpnchain.app

import androidx.compose.foundation.layout.fillMaxSize
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
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.ui.shouldUseDarkTheme
import com.verdenroz.vpnchain.feature.chain.navigation.CHAIN_ROUTE
import com.verdenroz.vpnchain.feature.chain.navigation.chainScreen
import com.verdenroz.vpnchain.feature.logs.navigation.logsScreen
import com.verdenroz.vpnchain.feature.settings.ImportRequest
import com.verdenroz.vpnchain.feature.settings.QrScanRequest
import com.verdenroz.vpnchain.feature.settings.navigation.settingsScreen
import com.verdenroz.vpnchain.feature.sites.navigation.sitesScreen
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

        val isSelected = { destination: TopLevelDestination ->
            currentDestination?.hierarchy?.any { it.route == destination.route } == true
        }
        val onSelect = { destination: TopLevelDestination -> navController.switchTo(destination) }

        AppShell(tunnelState, isSelected, onSelect, banner = { UpdateBanner(horizontalPadding = it) }) {
            NavHost(
                navController = navController,
                startDestination = CHAIN_ROUTE,
                modifier = Modifier.fillMaxSize(),
            ) {
                chainScreen(
                    onOpenSettings = { onSelect(TopLevelDestination.SETTINGS) },
                    onPairAgain = {
                        ImportRequest.request()
                        onSelect(TopLevelDestination.SETTINGS)
                    },
                )
                sitesScreen()
                logsScreen()
                settingsScreen()
            }
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
