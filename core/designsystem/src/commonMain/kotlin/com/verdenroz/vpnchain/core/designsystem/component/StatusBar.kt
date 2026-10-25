package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

enum class StatusTone { Connected, Connecting, Unprotected }

/** Full-width state banner. Reaches under the system status bar on Android. */
@Composable
fun StatusBar(
    tone: StatusTone,
    label: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
) {
    val colors = VpnChainTheme.colors
    val fill by animateColorAsState(
        when (tone) {
            StatusTone.Connected -> colors.connected
            StatusTone.Connecting -> colors.connecting
            StatusTone.Unprotected -> colors.unprotected
        },
        label = "status-bar-fill",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(fill)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(colors.onFilled, size = 10.dp)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onFilled,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
