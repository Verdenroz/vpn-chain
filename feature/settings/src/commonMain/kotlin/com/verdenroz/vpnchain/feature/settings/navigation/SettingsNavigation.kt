package com.verdenroz.vpnchain.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.verdenroz.vpnchain.feature.settings.SettingsRoute

const val SETTINGS_ROUTE = "settings"

fun NavController.navigateToSettings() = navigate(SETTINGS_ROUTE)

fun NavGraphBuilder.settingsScreen() {
    composable(SETTINGS_ROUTE) {
        SettingsRoute()
    }
}
