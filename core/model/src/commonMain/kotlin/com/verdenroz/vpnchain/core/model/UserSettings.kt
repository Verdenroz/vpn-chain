package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** App theme preference */
@Serializable
enum class ThemeConfig { FOLLOW_SYSTEM, LIGHT, DARK }

/** User-tunable app settings (the chain identity lives in [ChainProfile]). */
@Serializable
data class UserSettings(
    val themeConfig: ThemeConfig = ThemeConfig.FOLLOW_SYSTEM,
    /**
     * Desktop only: routes the whole system through TUN instead of the localhost
     * SOCKS proxy (requires CAP_NET_ADMIN). Android is always TUN, so this is
     * ignored there; defaults on to match Android's behavior.
     */
    val systemWideTun: Boolean = true,
    /**
     * Desktop only, and only meaningful with a WireGuard entry hop configured:
     * installs a blackhole-route kill switch before connecting. Ignored otherwise
     * — relay-only mode relies on Proton's own kill switch, Android on Always-on VPN.
     */
    val killSwitchEnabled: Boolean = true,
)
