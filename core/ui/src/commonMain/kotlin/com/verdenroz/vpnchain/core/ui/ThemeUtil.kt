package com.verdenroz.vpnchain.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.verdenroz.vpnchain.core.model.ThemeConfig

/** Resolves the effective dark/light choice from the user's [ThemeConfig]. */
@Composable
fun shouldUseDarkTheme(themeConfig: ThemeConfig): Boolean = when (themeConfig) {
    ThemeConfig.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    ThemeConfig.LIGHT -> false
    ThemeConfig.DARK -> true
}
