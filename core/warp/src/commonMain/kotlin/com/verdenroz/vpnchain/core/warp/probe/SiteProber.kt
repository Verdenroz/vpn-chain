package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.model.BlockReason

/** One GET of `https://<host>/` through a probe proxy; null on any transport failure. */
interface SiteFetcher {
    suspend fun get(host: String, port: Int, proxy: ProbeProxy): ProbeResponse?
}

expect fun createSiteFetcher(): SiteFetcher

sealed interface ProbeOutcome {
    /** The relay does not refuse it, so nothing needs routing. */
    data object Reachable : ProbeOutcome

    /** Refused from the relay, answered from WARP. */
    data class WarpOnly(val reason: BlockReason) : ProbeOutcome

    /** Refused from the relay and from WARP, with no fail-open path asked. */
    data class RefusedByBoth(val reason: BlockReason) : ProbeOutcome

    /** Refused from the relay and WARP, answered from the fail-open path. */
    data class FallbackOpen(val reason: BlockReason) : ProbeOutcome

    /**
     * Refused from every exit. The refusal is of the client, not the address,
     * so moving the site would change nothing but who sees it.
     */
    data class RefusedEverywhere(val reason: BlockReason) : ProbeOutcome

    /** A probe failed or said nothing either way, so there is nothing to act on. */
    data object Unknown : ProbeOutcome
}

/**
 * The relay is probed first, because the relay already sees every destination:
 * that probe tells it nothing new. A challenge there is a site a browser can
 * use, so only an outright refusal starts the chain of probes. WARP is asked
 * then, and the fail-open path only once WARP has refused or challenged too,
 * since failing open would expose the site to that path anyway.
 *
 * WARP has to answer cleanly to take a site: a challenge there is the shared
 * address being suspect, not the site being reachable. The fail-open path
 * takes a site it merely challenges, since a browser passes a challenge from an
 * ordinary address, and refuses one it blocks outright.
 */
class SiteProber(private val fetcher: SiteFetcher) {

    suspend fun probe(host: String, proxy: ProbeProxy): ProbeOutcome {
        val relayReason = when (val relay = verdict(host, proxy.relayPort, proxy)) {
            ExitVerdict.Open, ExitVerdict.Challenged -> return ProbeOutcome.Reachable
            ExitVerdict.Inconclusive -> return ProbeOutcome.Unknown
            is ExitVerdict.Blocked -> relay.reason
        }
        if (proxy.warpPort == 0) return ProbeOutcome.Unknown
        return when (verdict(host, proxy.warpPort, proxy, clean = true)) {
            ExitVerdict.Open -> ProbeOutcome.WarpOnly(relayReason)
            ExitVerdict.Challenged, is ExitVerdict.Blocked -> viaFallback(host, proxy, relayReason)
            ExitVerdict.Inconclusive -> ProbeOutcome.Unknown
        }
    }

    private suspend fun viaFallback(host: String, proxy: ProbeProxy, reason: BlockReason): ProbeOutcome {
        if (proxy.fallbackPort == 0) return ProbeOutcome.RefusedByBoth(reason)
        return when (verdict(host, proxy.fallbackPort, proxy)) {
            ExitVerdict.Open, ExitVerdict.Challenged -> ProbeOutcome.FallbackOpen(reason)
            is ExitVerdict.Blocked -> ProbeOutcome.RefusedEverywhere(reason)
            ExitVerdict.Inconclusive -> ProbeOutcome.Unknown
        }
    }

    /** With [clean], an answer is only open when it is a 2xx or 3xx. */
    private suspend fun verdict(host: String, port: Int, proxy: ProbeProxy, clean: Boolean = false): ExitVerdict {
        val response = fetcher.get(host, port, proxy) ?: return ExitVerdict.Inconclusive
        val verdict = SiteClassifier.classify(response)
        return if (clean && verdict == ExitVerdict.Open && response.status !in 200..399) ExitVerdict.Inconclusive else verdict
    }
}
