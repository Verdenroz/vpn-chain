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
 * still goes through the relay. Reads the topology as dialled: a disabled entry
 * hop, or desktop's proxy mode, which never dials one, reads as no entry hop.
 * There a [LearnedRoute.FailOpen] site stays on the relay, and only a site the
 * user sent [LearnedRoute.Direct] leaves by the bare link. A WireGuard-only
 * connection has no relay to bypass, so nothing on it fails open.
 */
fun LearnedSite.outsideExit(settings: UserSettings, profile: SavedProfile?): OutsideExit? {
    if (route != LearnedRoute.FailOpen && route != LearnedRoute.Direct) return null
    if (!settings.failOpenActive) return null
    if (profile != null && !profile.supportsWarpExit) return null
    // Android never changes this desktop-only setting, so it always reads as TUN there.
    val entryDialled = settings.systemWideTun && profile?.effectiveFor(settings)?.wireGuard != null
    return when {
        entryDialled -> OutsideExit.EntryHop
        route == LearnedRoute.Direct -> OutsideExit.Direct
        else -> null
    }
}
