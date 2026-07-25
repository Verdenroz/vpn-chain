package com.verdenroz.vpnchain.core.config

/**
 * Local sing-box control endpoint, used only to read traffic counters.
 *
 * [secret] is not optional in practice: the clash API can close connections and
 * switch outbounds, so an unauthenticated listener would let any local process
 * drive the tunnel. Bound to loopback and carried in memory rather than
 * persisted, so it dies with the session that created it.
 */
data class ClashApi(
    val port: Int,
    val secret: String,
) {
    val externalController: String get() = "127.0.0.1:$port"
}
