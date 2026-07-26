package com.verdenroz.vpnchain.core.model

/** High-level state of the layered chain, surfaced to the UI. */
enum class TunnelState { Disconnected, Connecting, Connected, Error }

/**
 * Whether the chain is fail-closed, and if not, why. A bare boolean can't tell
 * "you turned it off" apart from "it's on but the helper is missing", which
 * leaves the UI giving advice that doesn't fix anything.
 */
enum class KillSwitchState {
    /** Fail-closed protection is active. */
    Active,

    /** No protection; a settings toggle is the way to get it. */
    Disabled,

    /** Enabled, but the privileged helper it needs isn't installed or usable. */
    HelperUnavailable,

    /** Desktop relay-only mode: only an external VPN app can fail-close here. */
    ExternalAppRequired,
}

data class ChainStatus(
    val state: TunnelState = TunnelState.Disconnected,
    /** True for either entry-hop form: this app's WireGuard peer, or an external VPN app. */
    val entryHopActive: Boolean = false,
    val killSwitch: KillSwitchState = KillSwitchState.Disabled,
    val exitIp: String? = null,
    val detail: UiText? = null,
)
