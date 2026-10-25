package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** Where a learned site leaves the chain. */
enum class LearnedRoute {
    /** The relay refuses it and WARP answers, so it takes the tail. */
    Warp,

    /** Both refuse it, so it stays on the relay. */
    RefusedByBoth,

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
