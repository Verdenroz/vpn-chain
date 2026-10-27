package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** Where a learned site leaves the chain. */
enum class LearnedRoute {
    /** The relay refuses it and WARP answers, so it takes the tail. */
    Warp,

    /** Both refuse it and no path outside the chain was available to try. */
    RefusedByBoth,

    /** Both refuse it, and the entry hop alone reaches it. Waits for the user to allow it ([FailOpen]). */
    EntryAvailable,

    /** The user chose to send it by the entry hop alone. */
    FailOpen,

    /**
     * Every exit up to the bare link refuses it, or there is no entry hop to try.
     * Nothing is sent that way until the user allows it ([Direct]).
     */
    DirectAvailable,

    /** The user chose to send it by the bare link. */
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
