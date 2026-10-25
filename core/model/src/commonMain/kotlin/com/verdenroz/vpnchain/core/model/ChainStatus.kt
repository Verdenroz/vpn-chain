package com.verdenroz.vpnchain.core.model

/** High-level state of the layered chain, surfaced to the UI. */
enum class TunnelState { Disconnected, Connecting, Connected, Error }

/**
 * Whether the chain is fail-closed, and if not, why. A bare boolean can't tell
 * "you turned it off" apart from "it's on but the helper can't run", which
 * leaves the UI giving advice that doesn't fix anything.
 */
enum class KillSwitchState {
    /** Fail-closed protection is active. */
    Active,

    /** No protection; a settings toggle is the way to get it. */
    Disabled,

    /** Enabled, but the helper binary isn't on PATH. */
    HelperNotFound,

    /** The helper runs but the firewall call is refused, i.e. it lacks cap_net_admin. */
    HelperUnprivileged,

    /** The helper ran and failed for a reason other than missing privileges. */
    HelperFailed,

    /** The helper didn't answer in time. */
    HelperTimedOut,

    /** Enabled, but the config yields no addresses to exempt; not a helper problem. */
    NoExemptAddresses,

    /** Enabled, but nothing installed the firewall for this session. */
    NotEngaged,

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
