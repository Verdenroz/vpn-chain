package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/** Where a learned site leaves the chain. */
enum class LearnedRoute {
    /** The relay refuses it and WARP answers, so it takes the tail. */
    Warp,

    /** Both refuse it, so it stays on the relay. */
    RefusedByBoth,
}

/**
 * A site the chain found out for itself, by probing. [reason] is the name of what the
 * relay answered with (a block reason), left for the UI to word.
 */
@Serializable
data class LearnedSite(
    val domain: String,
    val reason: String,
    val learnedAtMillis: Long,
    val route: LearnedRoute,
)
