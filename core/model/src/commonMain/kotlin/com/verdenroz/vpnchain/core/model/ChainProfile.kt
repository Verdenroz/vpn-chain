package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/**
 * A WireGuard peer that sing-box dials itself, so no separate VPN app is needed:
 * any provider's config, or your own box. As a chain's TUN-mode entry hop it is
 * optional; without one the chain is relay-only and the VPS sees the real IP.
 * Use a device-specific config so it can be revoked on its own.
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
    override fun toString() = "WireGuardEntry(endpoint=$endpointHost:$endpointPort)"

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
    override fun toString() = "ChainProfile(server=$vpsIp:$serverPort)"

    companion object {
        const val DEFAULT_SNI = "www.samsung.com"
        const val DEFAULT_SERVER_PORT = 443
        const val DEFAULT_LOCAL_PROXY_PORT = 1080
    }
}
