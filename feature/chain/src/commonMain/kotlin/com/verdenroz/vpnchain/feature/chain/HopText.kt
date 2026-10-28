package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.geoip.HopLocation

/** The short name on the map: the city, or the country where the table has no city. */
internal fun HopLocation.shortName(): String = city ?: countryName

/** What a hop row says: provider (entry only), then the city and country. */
internal fun hopRowValue(location: HopLocation, provider: String?): String {
    val place = listOfNotNull(location.city, location.countryName).joinToString(", ")
    return if (provider == null) place else "$provider · $place"
}

internal fun mapLabel(role: String, location: HopLocation): String = "$role · ${location.shortName()}"
