package com.verdenroz.vpnchain.core.geoip

/** A country's display name and an approximate (centroid) location. */
internal data class CountryGeo(val name: String, val lat: Double, val lon: Double)

/** Resolved location of a chain hop, at country-level precision. */
data class HopLocation(val countryCode: String, val countryName: String, val lat: Double, val lon: Double)
