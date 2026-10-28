package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.geoip.HopLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class HopTextTest {
    private val miami = HopLocation("US", "United States", 25.76, -80.19, city = "Miami")
    private val countryOnly = HopLocation("IS", "Iceland", 64.98, -18.57)

    @Test
    fun mapLabelNamesTheRoleAndTheCity() {
        assertEquals("Entry · Miami", mapLabel("Entry", miami))
    }

    @Test
    fun mapLabelFallsBackToTheCountry() {
        assertEquals("Exit · Iceland", mapLabel("Exit", countryOnly))
    }

    @Test
    fun rowNamesCityAndCountry() {
        assertEquals("Miami, United States", hopRowValue(miami, provider = null))
    }

    @Test
    fun rowLeadsWithTheProvider() {
        assertEquals("Proton · Miami, United States", hopRowValue(miami, provider = "Proton"))
    }

    @Test
    fun rowWithoutACityNamesTheCountry() {
        assertEquals("Iceland", hopRowValue(countryOnly, provider = null))
    }
}
