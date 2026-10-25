package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.config.ProbeProxy

/** One GET of `https://<host>/` through a probe proxy; null on any transport failure. */
interface SiteFetcher {
    suspend fun get(host: String, port: Int, proxy: ProbeProxy): ProbeResponse?
}

expect fun createSiteFetcher(): SiteFetcher

sealed interface ProbeOutcome {
    /** The relay is not refused, so nothing needs routing. */
    data object Reachable : ProbeOutcome

    /** Refused from the relay, answered from WARP. */
    data class WarpOnly(val reason: BlockReason) : ProbeOutcome

    /** Refused from the relay and from WARP. */
    data class RefusedByBoth(val reason: BlockReason) : ProbeOutcome

    /** A probe failed or said nothing either way, so there is nothing to act on. */
    data object Unknown : ProbeOutcome
}

/**
 * The relay is probed first, because the relay already sees every destination:
 * that probe tells it nothing new. WARP is only asked once the relay refuses.
 */
class SiteProber(private val fetcher: SiteFetcher) {

    suspend fun probe(host: String, proxy: ProbeProxy): ProbeOutcome {
        val relayReason = when (val relay = verdict(host, proxy.relayPort, proxy)) {
            ExitVerdict.Open -> return ProbeOutcome.Reachable
            ExitVerdict.Inconclusive -> return ProbeOutcome.Unknown
            is ExitVerdict.Blocked -> relay.reason
        }
        if (proxy.warpPort == 0) return ProbeOutcome.Unknown
        return when (verdict(host, proxy.warpPort, proxy)) {
            ExitVerdict.Open -> ProbeOutcome.WarpOnly(relayReason)
            is ExitVerdict.Blocked -> ProbeOutcome.RefusedByBoth(relayReason)
            ExitVerdict.Inconclusive -> ProbeOutcome.Unknown
        }
    }

    private suspend fun verdict(host: String, port: Int, proxy: ProbeProxy): ExitVerdict =
        fetcher.get(host, port, proxy)?.let(SiteClassifier::classify) ?: ExitVerdict.Inconclusive
}
