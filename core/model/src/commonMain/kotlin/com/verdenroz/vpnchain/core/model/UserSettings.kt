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
     * any TUN chain this app dials itself. Deferred to an external VPN app's
     * own kill switch when that app is the entry hop, and ignored on Android,
     * where fail-closed is the system Always-on VPN setting.
     */
    val killSwitchEnabled: Boolean = true,
    /**
     * Blocklist filtering on the chain's resolver. On by default: a chain
     * with no entry hop has no upstream filter behind it, and losing the
     * filtering silently is worse than filtering something you wanted.
     */
    val dnsFilter: DnsFilter = DnsFilter.AdsAndTrackers,
    /**
     * Dial the relay through the profile's WireGuard hop when it has one. Off
     * means single-hop even with entry keys present — steadier when the entry
     * peer is flaky, at the cost of the VPS seeing this device's IP. Ignored
     * for profiles with no entry configured.
     */
    val entryHopEnabled: Boolean = true,
    /**
     * How much traffic takes the Cloudflare WARP tail after the relay. On by
     * default for everything: the relay's datacenter address is refused
     * outright by some sites, and the extra hop measured no slower. Cost is
     * that Cloudflare, not a box you own, is the last hop — drop to
     * [WarpMode.BlockedSites] or [WarpMode.Off] to take that back.
     */
    val warpMode: WarpMode = WarpMode.AllTraffic,
    /**
     * The domains [WarpMode.BlockedSites] routes down the tail — the whole
     * list, seeded from [DEFAULT_WARP_DOMAINS] and editable in full. Suffix
     * matches, so `example.com` covers its subdomains too.
     */
    val warpDomains: List<String> = DEFAULT_WARP_DOMAINS,
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

/**
 * The profile as the chain should actually dial it under these settings.
 * Every consumer of the topology — config rendering, the route readout —
 * must go through this, or a disabled entry hop would still be drawn.
 */
fun ChainProfile.effectiveFor(settings: UserSettings): ChainProfile =
    if (settings.entryHopEnabled) this else copy(entryHop = null)
