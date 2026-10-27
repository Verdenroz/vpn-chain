package com.verdenroz.vpnchain.app

import com.verdenroz.vpnchain.app.generated.resources.Res
import com.verdenroz.vpnchain.app.generated.resources.nav_chain
import com.verdenroz.vpnchain.app.generated.resources.nav_logs
import com.verdenroz.vpnchain.app.generated.resources.nav_settings
import com.verdenroz.vpnchain.app.generated.resources.nav_sites
import com.verdenroz.vpnchain.feature.chain.navigation.CHAIN_ROUTE
import com.verdenroz.vpnchain.feature.logs.navigation.LOGS_ROUTE
import com.verdenroz.vpnchain.feature.settings.navigation.SETTINGS_ROUTE
import com.verdenroz.vpnchain.feature.sites.navigation.SITES_ROUTE
import org.jetbrains.compose.resources.StringResource

/** The top-level screens, in tab order. */
enum class TopLevelDestination(val route: String, val label: StringResource) {
    CHAIN(CHAIN_ROUTE, Res.string.nav_chain),
    SITES(SITES_ROUTE, Res.string.nav_sites),
    SETTINGS(SETTINGS_ROUTE, Res.string.nav_settings),
    LOGS(LOGS_ROUTE, Res.string.nav_logs),
}
