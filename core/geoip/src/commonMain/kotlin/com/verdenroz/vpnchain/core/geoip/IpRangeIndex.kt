package com.verdenroz.vpnchain.core.geoip

import com.verdenroz.vpnchain.core.geoip.generated.resources.Res

private const val RECORD_SIZE = 6 // 4-byte big-endian start IP + 2-byte ASCII country code

/** Binary search over a sorted (startIp, countryCode) table — see `licenses/ATTRIBUTION.txt`. */
internal class IpRangeIndex private constructor(private val data: ByteArray) {
    private val recordCount = data.size / RECORD_SIZE

    fun countryCodeFor(ip: Long): String {
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
        return countryCodeAt(match)
    }

    private fun startIpAt(index: Int): Long {
        val offset = index * RECORD_SIZE
        return (data[offset].toLong() and 0xFF shl 24) or
            (data[offset + 1].toLong() and 0xFF shl 16) or
            (data[offset + 2].toLong() and 0xFF shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun countryCodeAt(index: Int): String {
        val offset = index * RECORD_SIZE + 4
        return data.decodeToString(offset, offset + 2)
    }

    companion object {
        suspend fun load(): IpRangeIndex = IpRangeIndex(Res.readBytes("files/geoip_ranges.bin"))
    }
}

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
