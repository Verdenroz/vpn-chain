package com.verdenroz.vpnchain.core.geoip

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Country-level location lookup for a chain hop's host or IP. */
interface RouteGeolocator {
    suspend fun locate(hostOrIp: String): HopLocation?
}

/** Backed by the bundled range table, which is loaded lazily and only once. */
class DefaultRouteGeolocator : RouteGeolocator {
    private val mutex = Mutex()
    private var index: IpRangeIndex? = null

    override suspend fun locate(hostOrIp: String): HopLocation? {
        val ip = parseIpv4(hostOrIp) ?: parseIpv4(resolveHostToIp(hostOrIp) ?: return null) ?: return null
        val code = ensureIndex().countryCodeFor(ip)
        val geo = COUNTRY_GEO[code] ?: return null
        return HopLocation(countryCode = code, countryName = geo.name, lat = geo.lat, lon = geo.lon)
    }

    private suspend fun ensureIndex(): IpRangeIndex =
        index ?: mutex.withLock { index ?: IpRangeIndex.load().also { index = it } }
}
