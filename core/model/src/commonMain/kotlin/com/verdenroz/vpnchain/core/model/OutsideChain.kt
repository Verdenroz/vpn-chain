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
 * entry hop reads as the bare link.
 */
fun LearnedSite.outsideExit(settings: UserSettings, profile: ChainProfile?): OutsideExit? {
    if (route != LearnedRoute.FailOpen || !settings.failOpenActive) return null
    return if (profile?.effectiveFor(settings)?.entryHop != null) OutsideExit.EntryHop else OutsideExit.Direct
}
