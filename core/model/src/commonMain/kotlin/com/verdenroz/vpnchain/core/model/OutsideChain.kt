package com.verdenroz.vpnchain.core.model

/** Where a site leaves when it does not go through the full chain. */
enum class OutsideExit {
    /** The entry hop alone: the site sees the entry provider's address. */
    EntryHop,

    /** The bare link: the site sees this network's own address. */
    Direct,
}

/**
 * Where a site leaving the relay goes under [settings], or null when nothing
 * may leave it. Reads the topology as dialled: a disabled entry hop, or
 * desktop's proxy mode, which never dials one, reads as no entry hop. A
 * WireGuard-only connection has no relay to bypass.
 */
fun SavedProfile?.outsideExitFor(settings: UserSettings): OutsideExit? {
    if (!settings.failOpenActive) return null
    if (this != null && !supportsWarpExit) return null
    // Android never changes this desktop-only setting, so it always reads as TUN there.
    val entryDialled = settings.systemWideTun && this?.effectiveFor(settings)?.wireGuard != null
    return if (entryDialled) OutsideExit.EntryHop else OutsideExit.Direct
}

/** Whether a site both exits refuse may be sent over the bare link once the user allows it. */
fun SavedProfile?.offersDirect(settings: UserSettings): Boolean =
    this != null && outsideExitFor(settings) == OutsideExit.Direct

/**
 * The way [this] leaves outside the chain, or null when it still goes through
 * the relay. A [LearnedRoute.FailOpen] site was checked through the entry hop
 * and leaves only by it; only a site the user sent [LearnedRoute.Direct] may
 * take the bare link.
 */
fun LearnedSite.outsideExit(settings: UserSettings, profile: SavedProfile?): OutsideExit? = when (route) {
    LearnedRoute.Direct -> profile.outsideExitFor(settings)
    LearnedRoute.FailOpen -> profile.outsideExitFor(settings)?.takeIf { it == OutsideExit.EntryHop }
    else -> null
}
