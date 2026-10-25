package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalVpnChainColors = staticCompositionLocalOf { DarkVpnChainColors }

/** Material supplies behaviour and accessibility underneath; colour and type are this app's own. */
@Composable
fun VpnChainTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkVpnChainColors else LightVpnChainColors
    CompositionLocalProvider(LocalVpnChainColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = vpnChainTypography(),
            content = content,
        )
    }
}

object VpnChainTheme {
    val colors: VpnChainColors
        @Composable @ReadOnlyComposable
        get() = LocalVpnChainColors.current
}
