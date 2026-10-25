package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.RuleSetKind
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The sites detection has found, and the rule-set files that carry them to sing-box. */
interface LearnedSitesRepository {
    val sites: Flow<List<LearnedSite>>

    suspend fun isSuppressed(domain: String): Boolean

    suspend fun learn(domain: String, route: LearnedRoute, reason: BlockReason)

    /** Drops the entry and keeps it from being learned again for a week. */
    suspend fun remove(domain: String)

    /**
     * Sends a [LearnedRoute.DirectAvailable] site down the bare link. Only the
     * user's tap calls this: nothing probes or routes a site there on its own.
     */
    suspend fun allowDirect(domain: String)

    /** Takes back [allowDirect]: the site is offered again and leaves the bare link. */
    suspend fun forget(domain: String)

    /** Domains found reachable on the relay and when that verdict lapses, so a restart does not re-probe them. */
    suspend fun reachable(): Map<String, Long>

    suspend fun saveReachable(domains: Map<String, Long>)

    /** Rewrites the rule-set files from storage; a running sing-box reloads them. */
    suspend fun syncRuleSets()
}

internal class DefaultLearnedSitesRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val store: LearnedRuleSetStore,
    private val now: () -> Long,
) : LearnedSitesRepository {

    override val sites: Flow<List<LearnedSite>> = preferences.learnedSites

    override suspend fun isSuppressed(domain: String): Boolean =
        (preferences.learnedSuppressions.first()[domain] ?: 0L) > now()

    override suspend fun learn(domain: String, route: LearnedRoute, reason: BlockReason) {
        if (isSuppressed(domain)) return
        val before = preferences.learnedSites.first().find { it.domain == domain }?.route
        preferences.putLearnedSite(LearnedSite(domain, reason, now(), route))
        // Each write reloads the rule-set in sing-box; a refusal routes nothing.
        if (before in ROUTED || route in ROUTED) syncRuleSets()
    }

    override suspend fun remove(domain: String) {
        preferences.removeLearnedSite(domain, nowMillis = now(), suppressUntilMillis = now() + SUPPRESS_MILLIS)
        syncRuleSets()
    }

    override suspend fun allowDirect(domain: String) = reroute(domain, from = LearnedRoute.DirectAvailable, to = LearnedRoute.Direct)

    override suspend fun forget(domain: String) = reroute(domain, from = LearnedRoute.Direct, to = LearnedRoute.DirectAvailable)

    private suspend fun reroute(domain: String, from: LearnedRoute, to: LearnedRoute) {
        val site = preferences.learnedSites.first().find { it.domain == domain && it.route == from } ?: return
        preferences.putLearnedSite(site.copy(route = to))
        syncRuleSets()
    }

    override suspend fun reachable(): Map<String, Long> = preferences.reachableDomains.first()

    override suspend fun saveReachable(domains: Map<String, Long>) =
        preferences.setReachableDomains(domains)

    override suspend fun syncRuleSets() {
        val learned = preferences.learnedSites.first()
        store.write(RuleSetKind.Warp, learned.filter { it.route == LearnedRoute.Warp }.map { it.domain })
        val direct = learned.filter { it.route == LearnedRoute.Direct }.map { it.domain }
        store.write(RuleSetKind.Open, learned.filter { it.route == LearnedRoute.FailOpen }.map { it.domain } + direct)
        store.write(RuleSetKind.Direct, direct)
    }

    private companion object {
        const val SUPPRESS_MILLIS = 7L * 24 * 60 * 60 * 1000
        val ROUTED = setOf(LearnedRoute.Warp, LearnedRoute.FailOpen, LearnedRoute.Direct)
    }
}
