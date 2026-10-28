package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.geoip.HopLocation

internal fun HopLocation.shortName(): String = city ?: countryName

internal fun hopRowValue(location: HopLocation, provider: String?): String {
    val place = listOfNotNull(location.city, location.countryName).joinToString(", ")
    return if (provider == null) place else "$provider · $place"
}

internal fun mapLabel(role: String, location: HopLocation): String = "$role · ${location.shortName()}"
