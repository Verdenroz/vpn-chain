package com.verdenroz.vpnchain.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.verdenroz.vpnchain.app.VpnChainApp
import com.verdenroz.vpnchain.app.di.appModules
import com.verdenroz.vpnchain.desktop.generated.resources.Res
import com.verdenroz.vpnchain.desktop.generated.resources.app_icon
import com.verdenroz.vpnchain.desktop.generated.resources.window_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin

fun main() {
    startKoin { modules(appModules) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.window_title),
            icon = painterResource(Res.drawable.app_icon),
        ) {
            VpnChainApp()
        }
    }
}
