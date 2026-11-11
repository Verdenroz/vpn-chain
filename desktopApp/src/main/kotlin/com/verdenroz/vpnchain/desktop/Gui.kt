package com.verdenroz.vpnchain.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.verdenroz.vpnchain.app.VpnChainApp
import com.verdenroz.vpnchain.app.di.engineModules
import com.verdenroz.vpnchain.app.di.uiModules
import com.verdenroz.vpnchain.core.common.appVersion
import com.verdenroz.vpnchain.core.common.systemTrayAvailable
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.engine.EnginePaths
import com.verdenroz.vpnchain.core.engine.WindowRequests
import com.verdenroz.vpnchain.core.engine.claimOrHandOff
import com.verdenroz.vpnchain.core.engine.di.controlServerModule
import com.verdenroz.vpnchain.core.engine.di.startHostedEngine
import com.verdenroz.vpnchain.core.logging.createPlatformLogger
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.desktop.generated.resources.Res
import com.verdenroz.vpnchain.desktop.generated.resources.app_icon
import com.verdenroz.vpnchain.desktop.generated.resources.tray_cancel
import com.verdenroz.vpnchain.desktop.generated.resources.tray_connect
import com.verdenroz.vpnchain.desktop.generated.resources.tray_disconnect
import com.verdenroz.vpnchain.desktop.generated.resources.tray_open
import com.verdenroz.vpnchain.desktop.generated.resources.tray_quit
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_connected
import com.verdenroz.vpnchain.desktop.generated.resources.tray_unblock_network
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_connecting
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_disconnected
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_error
import com.verdenroz.vpnchain.desktop.generated.resources.window_title
import kotlin.system.exitProcess
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin

internal fun runGui() {
    // Before any UI or engine exists: a second copy must never drive the shared relay unit.
    val lock = claimOrHandOff(EnginePaths.system(), appVersion, createPlatformLogger()) ?: exitProcess(0)
    installShutdownWatchdog()
    // Resolved from the Koin instance directly rather than through Compose
    // locals: this runs outside any KoinApplication composition.
    val koin = startKoin { modules(engineModules + controlServerModule + uiModules) }.koin
    startHostedEngine(koin, lock)
    application {
        val chain = remember { koin.get<ChainRepository>() }
        val settingsRepository = remember { koin.get<SettingsRepository>() }
        val scope = rememberCoroutineScope()

        val status by chain.status.collectAsState(ChainStatus())
        val settings by settingsRepository.settings.collectAsState(UserSettings())

        // The window can be closed while the chain keeps running, so the tray —
        // not the window — owns the app's lifetime from here on.
        var windowVisible by remember { mutableStateOf(true) }
        var raiseCount by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            koin.get<WindowRequests>().requests.collect {
                windowVisible = true
                raiseCount++
            }
        }
        val trayState = rememberTrayState()
        // Connecting counts: the kill switch can already be up, and Cancel has to reach it.
        val up = status.state == TunnelState.Connected || status.state == TunnelState.Connecting
        var quitPrompt by remember { mutableStateOf(false) }
        val quit = { if (status.networkBlocked) quitPrompt = true else exitApplication() }
        // Hiding the window without a tray to restore it from would leave a
        // running VPN with no way to reach it.
        val hideOnClose = settings.closeToTray && systemTrayAvailable

        if (systemTrayAvailable) Tray(
            state = trayState,
            icon = remember(status.state) { TrayLamp(status.state) },
            tooltip = stringResource(
                when (status.state) {
                    TunnelState.Connected -> Res.string.tray_tooltip_connected
                    TunnelState.Connecting -> Res.string.tray_tooltip_connecting
                    TunnelState.Error -> Res.string.tray_tooltip_error
                    TunnelState.Disconnected -> Res.string.tray_tooltip_disconnected
                },
            ),
            onAction = { windowVisible = true },
            menu = {
                Item(stringResource(Res.string.tray_open), onClick = { windowVisible = true })
                Item(
                    stringResource(
                        when (status.state) {
                            TunnelState.Connected -> Res.string.tray_disconnect
                            TunnelState.Connecting -> Res.string.tray_cancel
                            TunnelState.Disconnected, TunnelState.Error -> Res.string.tray_connect
                        },
                    ),
                    // The same repository call the Connect button makes,
                    // so the tray never drives the tunnel by a second path.
                    onClick = {
                        scope.launch { if (up) chain.disconnect() else chain.connect() }
                    },
                )
                if (status.blockedWhileDown) {
                    Item(
                        stringResource(Res.string.tray_unblock_network),
                        onClick = { scope.launch { chain.disconnect() } },
                    )
                }
                Separator()
                Item(stringResource(Res.string.tray_quit), onClick = quit)
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    if (hideOnClose) windowVisible = false else quit()
                },
                title = stringResource(Res.string.window_title),
                icon = painterResource(Res.drawable.app_icon),
            ) {
                LaunchedEffect(raiseCount) { window.toFront() }
                VpnChainApp()
            }
        }

        if (quitPrompt) {
            QuitPrompt(
                themeConfig = settings.themeConfig,
                onUnblockAndQuit = {
                    scope.launch {
                        chain.disconnect()
                        exitApplication()
                    }
                },
                onQuit = ::exitApplication,
                onStay = { quitPrompt = false },
            )
        }
    }
}
