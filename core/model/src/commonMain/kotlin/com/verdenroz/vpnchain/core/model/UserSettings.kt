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
     * Desktop only: installs the nftables kill switch before connecting, for
     * any TUN chain this app dials itself. Deferred to the real ProtonVPN app's
     * own kill switch when that app is the entry hop, and ignored on Android,
     * where fail-closed is the system Always-on VPN setting.
     */
    val killSwitchEnabled: Boolean = true,
    /**
     * Blocklist filtering on the chain's resolver. On by default: a chain
     * without a Proton entry hop has no NetShield behind it, and losing the
     * filtering silently is worse than filtering something you wanted.
     */
    val dnsFilter: DnsFilter = DnsFilter.AdsAndTrackers,
    /**
     * Bring the chain up on app start when a profile exists. Off by default:
     * connecting without being asked is a surprise, not a convenience.
     */
    val autoConnectOnLaunch: Boolean = false,
    /**
     * Re-establish the chain after an unexpected drop or a network change.
     * On by default — a tunnel that dies silently is the failure mode this
     * whole app exists to avoid.
     */
    val autoReconnect: Boolean = true,
    /**
     * Desktop only: register a login item so the app starts with the session.
     * Persisted only once the OS-level entry is actually written.
     */
    val autoStartOnLogin: Boolean = false,
    /**
     * Desktop only: closing the window hides it to the tray instead of quitting.
     * On by default — a VPN you dismissed is not a VPN you meant to turn off.
     */
    val closeToTray: Boolean = true,
)
