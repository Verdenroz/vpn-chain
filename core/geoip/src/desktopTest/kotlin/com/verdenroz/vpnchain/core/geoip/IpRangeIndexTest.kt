package com.verdenroz.vpnchain.core.geoip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IpRangeIndexTest {
    private val index = File("src/commonMain/composeResources/files").let { dir ->
        IpRangeIndex(File(dir, "geoip_ranges.bin").readBytes(), parseCities(File(dir, "geoip_cities.tsv").readBytes()))
    }

    private fun lookup(ip: String) = index.lookup(parseIpv4(ip)!!)

    @Test
    fun publicRangesResolveToTheirCity() {
        assertEquals("Sydney", lookup("1.1.1.1").city?.name)
        assertEquals("Amsterdam", lookup("193.0.0.1").city?.name)
        assertEquals("New York", lookup("208.67.222.222").city?.name)
    }

    @Test
    fun cityCarriesItsOwnCoordinates() {
        val city = assertNotNull(lookup("208.67.222.222").city)
        assertEquals(40.7, city.lat, 0.5)
        assertEquals(-74.0, city.lon, 0.5)
    }

    @Test
    fun documentationRangesHaveNoLocation() {
        listOf("192.0.2.1", "198.51.100.7", "203.0.113.5").forEach { ip ->
            assertEquals("ZZ", lookup(ip).countryCode)
            assertNull(lookup(ip).city)
        }
    }

    @Test
    fun rangeWithoutACityKeepsTheCountry() {
        val hit = lookup("84.17.40.1")
        assertEquals("US", hit.countryCode)
        assertNull(hit.city)
    }

    @Test
    fun privateSpaceHasNoCountry() {
        assertEquals("ZZ", lookup("10.1.2.3").countryCode)
    }

    @Test
    fun parsesCityLines() {
        val cities = parseCities("40.71\t-74.01\tNew York\n-33.87\t151.21\tSydney\n".encodeToByteArray())
        assertEquals(listOf("New York", "Sydney"), cities.map { it.name })
        assertEquals(151.21, cities[1].lon)
    }
}
