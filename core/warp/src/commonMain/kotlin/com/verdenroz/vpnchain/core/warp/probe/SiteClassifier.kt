package com.verdenroz.vpnchain.core.warp.probe

/** Why an exit was refused; [label] is what the settings list shows. */
enum class BlockReason(val label: String) {
    Forbidden("403"),
    RateLimited("429"),
    LegalBlock("451"),
    NetworkSecurity("network security"),
    Challenge("challenge"),
}

sealed interface ExitVerdict {
    data object Open : ExitVerdict
    data class Blocked(val reason: BlockReason) : ExitVerdict

    /** A server error or similar: says nothing about the address. */
    data object Inconclusive : ExitVerdict
}

/**
 * Tells a site refusing an address from a site answering. Markers are only
 * read in a page that arrived whole or came with an error status: block and
 * challenge pages are small, and a long article quoting one is not a block.
 */
object SiteClassifier {

    fun classify(response: ProbeResponse): ExitVerdict {
        val scanned = response.complete || response.status !in 200..299
        val reason = (if (scanned) markerIn(response) else null) ?: when (response.status) {
            403 -> BlockReason.Forbidden
            429 -> BlockReason.RateLimited
            451 -> BlockReason.LegalBlock
            else -> null
        }
        return when {
            reason != null -> ExitVerdict.Blocked(reason)
            response.status >= 500 -> ExitVerdict.Inconclusive
            else -> ExitVerdict.Open
        }
    }

    private fun markerIn(response: ProbeResponse): BlockReason? {
        val body = response.body.lowercase()
        return when {
            "blocked by network security" in body -> BlockReason.NetworkSecurity
            response.headers["cf-mitigated"] == "challenge" -> BlockReason.Challenge
            CHALLENGE_MARKERS.any { it in body } -> BlockReason.Challenge
            else -> null
        }
    }

    private val CHALLENGE_MARKERS = listOf(
        "name=\"js_challenge\"",
        "<title>just a moment...</title>",
        "<title>attention required! | cloudflare</title>",
        "/cdn-cgi/challenge-platform/",
    )
}
