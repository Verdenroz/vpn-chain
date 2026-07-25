package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ClashApi
import java.net.HttpURLConnection
import java.net.URI

/** Cumulative bytes since the relay started, as clash reports them. */
internal data class TrafficTotals(val uplinkBytes: Long, val downlinkBytes: Long)

/**
 * Reads traffic counters off sing-box's clash API.
 *
 * `/connections` is polled rather than `/traffic` because the latter is a
 * streaming endpoint of per-second rates; totals let rates be derived from
 * deltas without holding a long-lived connection open against our own tunnel.
 */
internal object ClashStatsClient {

    fun fetchTotals(api: ClashApi): TrafficTotals? = runCatching {
        val connection = URI("http://${api.externalController}/connections").toURL()
            .openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${api.secret}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            parseTotals(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Scanned rather than deserialized: the payload also carries every live
     * connection, and the two totals are all this needs.
     */
    fun parseTotals(body: String): TrafficTotals? {
        val up = UPLOAD_TOTAL.find(body)?.groupValues?.get(1)?.toLongOrNull()
        val down = DOWNLOAD_TOTAL.find(body)?.groupValues?.get(1)?.toLongOrNull()
        if (up == null || down == null) return null
        return TrafficTotals(uplinkBytes = up, downlinkBytes = down)
    }

    private const val TIMEOUT_MS = 2_000
    private val UPLOAD_TOTAL = Regex("\"uploadTotal\"\\s*:\\s*(\\d+)")
    private val DOWNLOAD_TOTAL = Regex("\"downloadTotal\"\\s*:\\s*(\\d+)")
}

/**
 * Per-second rate from two cumulative samples.
 *
 * Guards the two ways this goes wrong in practice: a restarted relay resets the
 * counters (a negative delta, which would render as a nonsense spike), and two
 * samples in the same millisecond would divide by zero.
 */
internal fun rateBytesPerSecond(
    previousBytes: Long,
    currentBytes: Long,
    elapsedMillis: Long,
): Long {
    if (elapsedMillis <= 0) return 0
    val delta = currentBytes - previousBytes
    if (delta <= 0) return 0
    return delta * 1_000 / elapsedMillis
}
