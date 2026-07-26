package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/**
 * Blocklist filtering applied to the chain's own resolver.
 *
 * Stands in for ProtonVPN's NetShield, which only exists when Proton is the
 * entry hop. A single-hop chain resolves through the relay instead, so the
 * filtering has to happen client-side — which also means it applies to every
 * mode, not just the ones that route through Proton.
 */
@Serializable
enum class DnsFilter {
    /** Resolve everything; the chain answers whatever the upstream does. */
    Off,

    /** Reject malware, phishing, and scam domains — NetShield's level 1. */
    Malware,

    /**
     * Reject ads and trackers on top of the malware set — NetShield's level 2.
     * The name predates the malware tier; it persists as-is in the datastore,
     * so it keeps the old name while meaning the full level.
     */
    AdsAndTrackers,
}
