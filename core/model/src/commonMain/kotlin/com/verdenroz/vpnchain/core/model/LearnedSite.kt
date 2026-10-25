package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** Where a learned site leaves the chain. */
enum class LearnedRoute {
    /** The relay refuses it and WARP answers, so it takes the tail. */
    Warp,

    /** Both refuse it and no path outside the chain was available to try. */
    RefusedByBoth,

    /** Both refuse it, and the entry hop alone reaches it. */
    FailOpen,

    /**
     * Both refuse it and there is no entry hop, so only the bare link is left.
     * Nothing is sent that way until the user allows it ([Direct]).
     */
    DirectAvailable,

    /** The user chose to send it by the bare link after both refused it. */
    Direct,

    /** Every path refuses it, so the refusal is about the client, not the address. */
    RefusedEverywhere,
}

/**
 * A site the chain found out for itself, by probing. [reason] is what the
 * relay answered with.
 */
@Serializable
data class LearnedSite(
    val domain: String,
    val reason: BlockReason,
    val learnedAtMillis: Long,
    val route: LearnedRoute,
)
