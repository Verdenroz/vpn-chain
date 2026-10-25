package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/**
 * WireGuard peer dialled by sing-box directly (no separate VPN app needed) —
 * any provider's config, or your own box. As the TUN-mode entry hop of a chain
 * it is optional: omitting it makes the chain relay-only, exposing the real IP
 * to the VPS. Use a device-specific config so it stays independently revocable.
 */
@Serializable
data class WireGuardEntry(
    val privateKey: String,
    /** Interface addresses with prefix, comma-separated, e.g. `10.2.0.2/32,fd00::2/128`. */
    val address: String,
    val peerPublicKey: String,
    val endpointHost: String,
    val endpointPort: Int = DEFAULT_ENDPOINT_PORT,
    val dns: String? = null,
    val presharedKey: String? = null,
    val mtu: Int? = null,
    val keepaliveSeconds: Int? = null,
) {
    companion object {
        const val DEFAULT_ENDPOINT_PORT = 51820
    }
}

/**
 * Identifiers for the VLESS+REALITY relay.
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
) {
    companion object {
        const val DEFAULT_SNI = "www.samsung.com"
        const val DEFAULT_SERVER_PORT = 443
        const val DEFAULT_LOCAL_PROXY_PORT = 1080
    }
}
