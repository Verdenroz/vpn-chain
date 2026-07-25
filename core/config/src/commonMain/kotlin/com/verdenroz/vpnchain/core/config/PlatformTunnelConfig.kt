package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile

/**
 * Renders the config shape the current platform's tunnel expects.
 *
 * Android is always a full `tun` chain (single VpnService). Desktop defaults to
 * the relay-only mixed inbound (SOCKS proxy; the OS Proton app is the entry hop),
 * but switches to the full `tun` chain when [systemWide] is set — capturing all
 * system traffic like the Android app.
 */
expect fun renderPlatformTunnelConfig(profile: ChainProfile, systemWide: Boolean): String
