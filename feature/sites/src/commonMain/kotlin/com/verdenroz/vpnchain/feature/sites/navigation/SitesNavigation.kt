package com.verdenroz.vpnchain.feature.sites.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.verdenroz.vpnchain.feature.sites.SitesRoute

const val SITES_ROUTE = "sites"

fun NavGraphBuilder.sitesScreen() {
    composable(SITES_ROUTE) {
        SitesRoute()
    }
}
