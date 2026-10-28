package com.verdenroz.vpnchain.core.geoip

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface RouteGeolocator {
    suspend fun locate(hostOrIp: String): HopLocation?
}

/** Backed by the bundled range table, which is loaded lazily and only once. */
class DefaultRouteGeolocator : RouteGeolocator {
    private val mutex = Mutex()
    private var index: IpRangeIndex? = null

    override suspend fun locate(hostOrIp: String): HopLocation? {
        val ip = parseIpv4(hostOrIp) ?: parseIpv4(resolveHost(hostOrIp) ?: return null) ?: return null
        val hit = ensureIndex().lookup(ip)
        val geo = COUNTRY_GEO[hit.countryCode] ?: return null
        return HopLocation(
            countryCode = hit.countryCode,
            countryName = geo.name,
            lat = hit.city?.lat ?: geo.lat,
            lon = hit.city?.lon ?: geo.lon,
            city = hit.city?.name,
        )
    }

    /** A hop configured by hostname needs the resolver, which a tunnel still
     *  coming up can leave unanswerable — a label is not worth stalling for. */
    private suspend fun resolveHost(host: String): String? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveHostToIp(host) }

    private suspend fun ensureIndex(): IpRangeIndex =
        index ?: mutex.withLock { index ?: IpRangeIndex.load().also { index = it } }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
