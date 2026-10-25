package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.app.generated.resources.Res
import com.verdenroz.vpnchain.app.generated.resources.status_connected
import com.verdenroz.vpnchain.app.generated.resources.status_connecting
import com.verdenroz.vpnchain.app.generated.resources.status_unprotected
import com.verdenroz.vpnchain.core.designsystem.component.StatusBar
import com.verdenroz.vpnchain.core.designsystem.component.StatusTone
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.TunnelState
import org.jetbrains.compose.resources.stringResource

private val WIDE_BREAKPOINT = 840.dp

/** Status bar across the top, then a nav rail beside the content when wide or tabs below it when narrow. */
@Composable
fun AppShell(
    tunnelState: TunnelState,
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(VpnChainTheme.colors.background)) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                tone = tunnelState.tone(),
                label = stringResource(tunnelState.labelRes()),
                horizontalPadding = if (wide) 24.dp else 20.dp,
            )
            val sides = if (wide) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal
            Row(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
                if (wide) NavRail(isSelected, onSelect)
                Column(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
            if (!wide) BottomTabs(isSelected, onSelect)
        }
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
