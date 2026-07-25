package com.verdenroz.vpnchain.core.geoip

/** What the internet answered, and how long the whole exchange took. */
data class PublicIpSample(val ip: String, val elapsedMs: Int)

/**
 * Network facts measured at runtime rather than read from config. The call can
 * fail and returns null when it does — a null has to surface as "unknown",
 * never as a plausible-looking default.
 */
interface NetworkProbe {
    /**
     * One request over whatever path is currently active: it reports the
     * address the far end saw, and the time a full round trip actually took.
     *
     * Deliberately a complete HTTP exchange, not a TCP connect. Under a TUN
     * with a userspace network stack the local stack answers the handshake
     * itself, so a connect time measures this machine — not the chain.
     */
    suspend fun samplePublicIp(): PublicIpSample?
}

expect fun createNetworkProbe(): NetworkProbe
