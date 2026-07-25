package com.verdenroz.vpnchain.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.verdenroz.vpnchain.app.VpnChainApp
import com.verdenroz.vpnchain.app.di.appModules
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.desktop.generated.resources.Res
import com.verdenroz.vpnchain.desktop.generated.resources.app_icon
import com.verdenroz.vpnchain.desktop.generated.resources.tray_connect
import com.verdenroz.vpnchain.desktop.generated.resources.tray_disconnect
import com.verdenroz.vpnchain.desktop.generated.resources.tray_open
import com.verdenroz.vpnchain.desktop.generated.resources.tray_quit
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_connected
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_connecting
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_disconnected
import com.verdenroz.vpnchain.desktop.generated.resources.tray_tooltip_error
import com.verdenroz.vpnchain.desktop.generated.resources.window_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin

fun main() {
    // Resolved from the Koin instance directly rather than through Compose
    // locals: this runs outside any KoinApplication composition.
    val koin = startKoin { modules(appModules) }.koin
    application {
        val chain = remember { koin.get<ChainRepository>() }
        val settingsRepository = remember { koin.get<SettingsRepository>() }
        val scope = rememberCoroutineScope()

        val status by chain.status.collectAsState(ChainStatus())
        val settings by settingsRepository.settings.collectAsState(UserSettings())

        // The window can be closed while the chain keeps running, so the tray —
        // not the window — owns the app's lifetime from here on.
        var windowVisible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val connected = status.state == TunnelState.Connected

        Tray(
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
                        if (connected) Res.string.tray_disconnect else Res.string.tray_connect,
                    ),
                    // The same repository call the panel's throw switch makes,
                    // so the tray never drives the tunnel by a second path.
                    onClick = {
                        scope.launch { if (connected) chain.disconnect() else chain.connect() }
                    },
                )
                Separator()
                Item(stringResource(Res.string.tray_quit), onClick = ::exitApplication)
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    if (settings.closeToTray) windowVisible = false else exitApplication()
                },
                title = stringResource(Res.string.window_title),
                icon = painterResource(Res.drawable.app_icon),
            ) {
                VpnChainApp()
            }
        }
    }
}
