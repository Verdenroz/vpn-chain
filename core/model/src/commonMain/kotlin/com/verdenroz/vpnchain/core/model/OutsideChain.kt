package com.verdenroz.vpnchain.core.model

/** Where a site leaves when it does not go through the full chain. */
enum class OutsideExit {
    /** The entry hop alone: the site sees the entry provider's address. */
    EntryHop,

    /** The bare link: the site sees this network's own address. */
    Direct,
}

/**
 * The way [this] leaves outside the chain under [settings], or null when it
 * still goes through the relay. Reads the topology as dialled, so a disabled
 * entry hop reads as the bare link. A WireGuard-only connection has no relay
 * to bypass, so nothing on it fails open.
 */
fun LearnedSite.outsideExit(settings: UserSettings, profile: SavedProfile?): OutsideExit? {
    if (route != LearnedRoute.FailOpen || !settings.failOpenActive) return null
    if (profile != null && !profile.supportsWarpExit) return null
    return if (profile?.effectiveFor(settings)?.wireGuard != null) OutsideExit.EntryHop else OutsideExit.Direct
}
