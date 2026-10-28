package com.verdenroz.vpnchain.core.geoip

import com.verdenroz.vpnchain.core.geoip.generated.resources.Res

private const val RECORD_SIZE = 8 // 4-byte start IP, 2-byte ASCII country code, 2-byte city id (0 = none)

internal class IndexedCity(val name: String, val lat: Double, val lon: Double)

internal class RangeHit(val countryCode: String, val city: IndexedCity?)

/** Binary search over a sorted (startIp, countryCode, city) table; see `licenses/ATTRIBUTION.txt`. */
internal class IpRangeIndex(private val data: ByteArray, private val cities: List<IndexedCity>) {
    private val recordCount = data.size / RECORD_SIZE

    fun lookup(ip: Long): RangeHit {
        var lo = 0
        var hi = recordCount - 1
        var match = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (startIpAt(mid) <= ip) {
                match = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val offset = match * RECORD_SIZE + 4
        val cityId = (data[offset + 2].toInt() and 0xFF shl 8) or (data[offset + 3].toInt() and 0xFF)
        return RangeHit(data.decodeToString(offset, offset + 2), cities.getOrNull(cityId - 1))
    }

    private fun startIpAt(index: Int): Long {
        val offset = index * RECORD_SIZE
        return (data[offset].toLong() and 0xFF shl 24) or
            (data[offset + 1].toLong() and 0xFF shl 16) or
            (data[offset + 2].toLong() and 0xFF shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    companion object {
        suspend fun load(): IpRangeIndex =
            IpRangeIndex(Res.readBytes("files/geoip_ranges.bin"), parseCities(Res.readBytes("files/geoip_cities.tsv")))
    }
}

internal fun parseCities(tsv: ByteArray): List<IndexedCity> =
    tsv.decodeToString().lineSequence().filter(String::isNotEmpty).map { line ->
        val (lat, lon, name) = line.split('\t')
        IndexedCity(name, lat.toDouble(), lon.toDouble())
    }.toList()

/** Parses a dotted-decimal IPv4 string; returns null for anything else (including hostnames). */
internal fun parseIpv4(value: String): Long? {
    val parts = value.trim().split(".")
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}
