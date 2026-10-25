package com.verdenroz.vpnchain.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.headlineMedium, color = VpnChainTheme.colors.ink)
}
