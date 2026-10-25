package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.model.BlockReason

sealed interface ExitVerdict {
    /** Answered, and nothing in the answer refuses this client. */
    data object Open : ExitVerdict

    /** A challenge a real browser would pass; an address that is merely suspect. */
    data object Challenged : ExitVerdict

    /** Refused outright: a refusing status or a block page. */
    data class Blocked(val reason: BlockReason) : ExitVerdict

    /** A server error or similar: says nothing about the address. */
    data object Inconclusive : ExitVerdict
}

/**
 * Tells a site refusing an address from a site answering, and a refusal from
 * a challenge. Markers are only read in a page that arrived whole or came with
 * an error status: block and challenge pages are small, and a long article
 * quoting one is not a block. A block page wins over a challenge marker, and a
 * challenge marker wins over the status it came with (Cloudflare serves its
 * challenge as a `403`).
 */
object SiteClassifier {

    fun classify(response: ProbeResponse): ExitVerdict {
        val scanned = response.complete || response.status !in 200..299
        val body = if (scanned) response.body.lowercase() else ""
        val challenged = scanned &&
            (response.headers["cf-mitigated"] == "challenge" || CHALLENGE_MARKERS.any { it in body })
        return when {
            "blocked by network security" in body -> ExitVerdict.Blocked(BlockReason.NetworkSecurity)
            challenged -> ExitVerdict.Challenged
            response.status == 403 -> ExitVerdict.Blocked(BlockReason.Forbidden)
            response.status == 429 -> ExitVerdict.Blocked(BlockReason.RateLimited)
            response.status == 451 -> ExitVerdict.Blocked(BlockReason.LegalBlock)
            response.status >= 500 -> ExitVerdict.Inconclusive
            else -> ExitVerdict.Open
        }
    }

    private val CHALLENGE_MARKERS = listOf(
        "name=\"js_challenge\"",
        "<title>just a moment...</title>",
        "<title>attention required! | cloudflare</title>",
        "/cdn-cgi/challenge-platform/",
    )
}
