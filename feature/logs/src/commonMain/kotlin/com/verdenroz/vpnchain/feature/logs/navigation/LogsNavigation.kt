package com.verdenroz.vpnchain.feature.logs.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.verdenroz.vpnchain.feature.logs.LogsRoute

const val LOGS_ROUTE = "logs"

fun NavController.navigateToLogs() = navigate(LOGS_ROUTE)

fun NavGraphBuilder.logsScreen() {
    composable(LOGS_ROUTE) {
        LogsRoute()
    }
}
