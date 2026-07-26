package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit

/**
 * Renders the config shape the current platform's tunnel expects.
 *
 * Android is always a full `tun` chain (single VpnService). Desktop defaults to
 * the relay-only mixed inbound (SOCKS proxy; an external VPN app is the entry hop),
 * but switches to the full `tun` chain when [UserSettings.systemWideTun] is set
 * — capturing all system traffic like the Android app.
 *
 * [warp] carries the tail exit's credentials, or null when there are none to
 * use — never registered, or registration failed. A null renders the chain
 * without a tail rather than refusing to render at all.
 */
expect fun renderPlatformTunnelConfig(
    profile: ChainProfile,
    settings: UserSettings,
    warp: WarpExit?,
): String
