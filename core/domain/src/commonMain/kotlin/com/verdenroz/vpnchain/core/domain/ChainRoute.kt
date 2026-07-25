package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.geoip.HopLocation

/** Position in the chain, ordered the way traffic travels. */
enum class HopRole { Origin, Entry, Exit }

/**
 * How this hop's address was learned. A configured value must never be
 * presented as a measured one — the panel states what it knows and how.
 */
enum class HopEvidence {
    /** Observed at runtime, just now. */
    Measured,

    /** Read from the profile: what gets dialled, not what was seen. */
    Configured,

    /** Measured earlier and recalled — the live value is hidden by the tunnel. */
    Recalled,

    /** Nothing to show. */
    Unknown,
}

data class ChainHop(
    val role: HopRole,
    val ip: String?,
    val location: HopLocation?,
    val evidence: HopEvidence,
    /** True when traffic is passing through this hop right now. */
    val carrying: Boolean,
    /** What carries traffic into this hop — always configuration, never measured. */
    val via: String? = null,
)

/**
 * The chain as currently observed. [throughRttMs] is a single end-to-end
 * measurement over whatever path is live; per-leg timings can't be isolated
 * from inside the tunnel, so they aren't invented.
 */
data class ChainRoute(
    val hops: List<ChainHop> = emptyList(),
    val throughRttMs: Int? = null,
) {
    fun hop(role: HopRole): ChainHop? = hops.firstOrNull { it.role == role }
}
