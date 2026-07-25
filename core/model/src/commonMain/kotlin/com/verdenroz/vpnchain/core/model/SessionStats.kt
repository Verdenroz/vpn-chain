package com.verdenroz.vpnchain.core.model

/**
 * Traffic and uptime for the current session, reset on every connect.
 *
 * Session-scoped on purpose: totals that survive a reconnect would answer a
 * different question ("how much have I ever moved") than the one the panel asks.
 */
data class SessionStats(
    /** When the tunnel reached Connected, or null while it isn't. */
    val connectedSinceMillis: Long? = null,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    /** Most recent sample, not an average over the session. */
    val uplinkBytesPerSecond: Long = 0,
    val downlinkBytesPerSecond: Long = 0,
) {
    /** False when the platform can't report traffic, so the UI shows placeholders. */
    val hasTraffic: Boolean get() = uplinkBytes > 0 || downlinkBytes > 0
}
