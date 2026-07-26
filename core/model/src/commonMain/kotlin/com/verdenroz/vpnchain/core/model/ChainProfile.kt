package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WireGuard peer dialled as the TUN-mode entry hop. Nothing here is provider
 * specific — Proton is what the docs assume, but any peer works. sing-box
 * dials it directly (no separate VPN app needed); use a device-specific config
 * so it stays independently revocable. Optional — omitting it makes the TUN
 * chain relay-only, exposing the device's real IP to the VPS.
 */
@Serializable
data class WireGuardEntry(
    val privateKey: String,
    /** Interface address with prefix, e.g. `10.2.0.2/32`. */
    val address: String,
    val peerPublicKey: String,
    val endpointHost: String,
    val endpointPort: Int = DEFAULT_ENDPOINT_PORT,
    val dns: String? = null,
) {
    companion object {
        const val DEFAULT_ENDPOINT_PORT = 51820
    }
}

/**
 * Identifiers for the VLESS+REALITY relay and the optional WireGuard entry hop.
 * Real values are supplied at runtime (never hardcoded in the repo).
 */
@Serializable
data class ChainProfile(
    val vpsIp: String,
    val vlessUuid: String,
    val realityPublicKey: String,
    val shortId: String,
    val sni: String = DEFAULT_SNI,
    val serverPort: Int = DEFAULT_SERVER_PORT,
    /** Local SOCKS/HTTP proxy the rendered relay listens on. */
    val localProxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
    /** TUN-mode entry hop; null = relay-only. Ignored by the SOCKS-proxy chain. */
    // Serialized name predates the rename; changing it would orphan saved profiles.
    @SerialName("protonEntry")
    val entryHop: WireGuardEntry? = null,
) {
    companion object {
        const val DEFAULT_SNI = "www.samsung.com"
        const val DEFAULT_SERVER_PORT = 443
        const val DEFAULT_LOCAL_PROXY_PORT = 1080
    }
}
