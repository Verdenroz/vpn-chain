package com.verdenroz.vpnchain.app

import com.verdenroz.vpnchain.feature.chain.navigation.CHAIN_ROUTE
import com.verdenroz.vpnchain.feature.logs.navigation.LOGS_ROUTE
import com.verdenroz.vpnchain.feature.settings.navigation.SETTINGS_ROUTE
import com.verdenroz.vpnchain.feature.sites.navigation.SITES_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals

class TopLevelDestinationTest {

    @Test
    fun `tabs run chain, sites, settings, logs`() {
        assertEquals(
            listOf(CHAIN_ROUTE, SITES_ROUTE, SETTINGS_ROUTE, LOGS_ROUTE),
            TopLevelDestination.entries.map { it.route },
        )
    }
}
