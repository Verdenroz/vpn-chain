package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.ui.shouldUseDarkTheme
import com.verdenroz.vpnchain.feature.chain.navigation.CHAIN_ROUTE
import com.verdenroz.vpnchain.feature.chain.navigation.chainScreen
import com.verdenroz.vpnchain.feature.logs.navigation.logsScreen
import com.verdenroz.vpnchain.feature.settings.QrScanRequest
import com.verdenroz.vpnchain.feature.settings.navigation.SETTINGS_ROUTE
import com.verdenroz.vpnchain.feature.settings.navigation.settingsScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VpnChainApp(appViewModel: AppViewModel = koinViewModel()) {
    val themeConfig by appViewModel.themeConfig.collectAsStateWithLifecycle()

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

        Scaffold(
            containerColor = PanelTheme.colors.shellDeep,
            bottomBar = {
                FaceplateSelector(
                    isSelected = { destination ->
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    },
                    onSelect = { destination -> navController.switchTo(destination) },
                )
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = CHAIN_ROUTE,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                chainScreen(onOpenSettings = { navController.navigate(SETTINGS_ROUTE) })
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

/**
 * Which faceplate is fitted. Selection is a lit lamp and a raised key rather
 * than Material's highlight pill — the chrome has to belong to the same object
 * as the panels it frames.
 */
@Composable
private fun FaceplateSelector(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colors = PanelTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.shellDeep)
            // The seam where the faceplate meets the base.
            .drawBehind {
                drawLine(colors.edgeShadow, Offset(0f, 0f), Offset(size.width, 0f), 1f)
                drawLine(
                    colors.edgeHighlight.copy(alpha = 0.35f),
                    Offset(0f, 1f),
                    Offset(size.width, 1f),
                    1f,
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = isSelected(destination)
            Column(
                Modifier
                    .weight(1f)
                    .height(TabHeight)
                    .machinedSurface(
                        colors,
                        corner = 2.dp,
                        recessed = !selected,
                        fill = if (selected) colors.shellRaised else colors.shellDeep,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(destination) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                IndicatorLamp(color = colors.lampGreen, lit = selected, size = 5.dp)
                Spacer(Modifier.height(1.dp))
                Text(
                    stringResource(destination.label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.readout else colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TabHeight = 48.dp
