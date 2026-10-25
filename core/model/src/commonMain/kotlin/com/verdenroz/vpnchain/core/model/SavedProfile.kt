package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

enum class ConnectionKind { Chain, Relay, WireGuard }

/**
 * A named connection the user can switch to: a VLESS relay, a WireGuard peer,
 * or both chained (WireGuard entry hop, then relay).
 *
 * The id is stable and separate from the name so renaming a profile — or
 * pointing it at a different relay — doesn't orphan the active selection.
 */
@Serializable
data class SavedProfile(
    val id: String,
    val name: String,
    val relay: ChainProfile? = null,
    val wireGuard: WireGuardEntry? = null,
) {
    init {
        require(relay != null || wireGuard != null) { "A connection needs a relay or a WireGuard peer" }
    }

    val kind: ConnectionKind
        get() = when {
            relay != null && wireGuard != null -> ConnectionKind.Chain
            relay != null -> ConnectionKind.Relay
            else -> ConnectionKind.WireGuard
        }

    /** The WARP tail and its auto-detect probing are dialled through the relay, so a bare WireGuard peer has neither. */
    val supportsWarpExit: Boolean
        get() = relay != null

    /** The address the user would recognise this connection by. */
    val host: String
        get() = relay?.vpsIp ?: wireGuard?.endpointHost.orEmpty()
}
