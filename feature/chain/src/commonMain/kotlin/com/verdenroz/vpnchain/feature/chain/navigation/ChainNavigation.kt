package com.verdenroz.vpnchain.feature.chain.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.verdenroz.vpnchain.feature.chain.ChainRoute

const val CHAIN_ROUTE = "chain"

fun NavController.navigateToChain() = navigate(CHAIN_ROUTE)

fun NavGraphBuilder.chainScreen(onOpenSettings: () -> Unit) {
    composable(CHAIN_ROUTE) {
        ChainRoute(onOpenSettings = onOpenSettings)
    }
}
