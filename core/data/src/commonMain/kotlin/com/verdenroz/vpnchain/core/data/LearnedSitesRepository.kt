package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRules
import com.verdenroz.vpnchain.core.config.RuleSetKind
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.tunnel.ConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.HostMatch
import com.verdenroz.vpnchain.core.warp.probe.PublicSuffixList
import com.verdenroz.vpnchain.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

/**
 * The sites detection has found, and the rule-set files that carry them to
 * sing-box. A change of route also closes the open connections it moves.
 */
interface LearnedSitesRepository {
    val sites: Flow<List<LearnedSite>>

    suspend fun isSuppressed(domain: String): Boolean

    /** Records a probe verdict; a [LearnedRoute.FailOpen] or [LearnedRoute.Direct] entry is never replaced by another. */
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

    /**
     * Rewrites the rule-set files from storage; a running sing-box reloads them.
     * One sync runs at a time. A write failure is thrown to the caller.
     */
    suspend fun syncRuleSets()
}

internal class DefaultLearnedSitesRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val store: LearnedRuleSetStore,
    private val closer: ConnectionCloser,
    private val now: () -> Long,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : LearnedSitesRepository {

    private val syncLock = Mutex()
    private var syncFailing = false
    private var closeFailing = false
    private val closeLock = Mutex()
    private val pendingCloses = mutableSetOf<HostMatch>()
    private var closeJob: Job? = null

    override val sites: Flow<List<LearnedSite>> = preferences.learnedSites

    override suspend fun isSuppressed(domain: String): Boolean =
        (preferences.learnedSuppressions.first()[domain] ?: 0L) > now()

    override suspend fun learn(domain: String, route: LearnedRoute, reason: BlockReason) {
        if (isSuppressed(domain)) return
        val before = preferences.learnedSites.first().find { it.domain == domain }?.route
        // A host on its way out of the relay stays there until the user changes it.
        if (before in KEPT && route != before) return
        preferences.putLearnedSite(LearnedSite(domain, reason, now(), route))
        // Each write reloads the rule-set in sing-box; a refusal routes nothing.
        if (before in ROUTED || route in ROUTED) syncAndReconnect(domain, before, route)
    }

    override suspend fun remove(domain: String) {
        val before = preferences.learnedSites.first().find { it.domain == domain }?.route
        preferences.removeLearnedSite(domain, nowMillis = now(), suppressUntilMillis = now() + SUPPRESS_MILLIS)
        syncAndReconnect(domain, before, null)
    }

    override suspend fun allowDirect(domain: String) = reroute(domain, from = LearnedRoute.DirectAvailable, to = LearnedRoute.Direct)

    override suspend fun forget(domain: String) = reroute(domain, from = LearnedRoute.Direct, to = LearnedRoute.DirectAvailable)

    private suspend fun reroute(domain: String, from: LearnedRoute, to: LearnedRoute) {
        val site = preferences.learnedSites.first().find { it.domain == domain && it.route == from } ?: return
        preferences.putLearnedSite(site.copy(route = to))
        syncAndReconnect(domain, from, to)
    }

    /**
     * Open connections keep the route they were dialled with, so once the files
     * are written, the ones a changed route covers are closed and redial under it.
     */
    private suspend fun syncAndReconnect(domain: String, before: LearnedRoute?, after: LearnedRoute?) {
        if (!syncKeepingState()) return
        val moved = before != after && (before in ROUTED || after in ROUTED)
        val match = HostMatch(domain, subdomains = LearnedRoute.Warp in setOf(before, after) && isWarpSuffix(domain))
        closeLock.withLock {
            if (moved) pendingCloses += match
            if (pendingCloses.isEmpty()) return
            // sing-box reloads a rule-set 100 ms after its file goes quiet; a redial before that keeps the old route.
            closeJob?.cancel()
            closeJob = scope.launch {
                delay(RELOAD_SETTLE_MILLIS)
                val matches = closeLock.withLock { pendingCloses.toList().also { pendingCloses.clear() } }
                withContext(NonCancellable) { matches.forEach { closeKeepingState(it) } }
            }
        }
    }

    override suspend fun reachable(): Map<String, Long> = preferences.reachableDomains.first()

    override suspend fun saveReachable(domains: Map<String, Long>) =
        preferences.setReachableDomains(domains)

    /**
     * WARP entries at a registrable domain match it and every name under it; an
     * entry that is a public suffix or a single host matches only itself.
     * Anything leaving the relay matches the exact host it was learned or sent for.
     */
    override suspend fun syncRuleSets() = syncLock.withLock {
        val learned = preferences.learnedSites.first()
        fun hosts(route: LearnedRoute) = learned.filter { it.route == route }.map { it.domain }
        val (suffixes, exact) = hosts(LearnedRoute.Warp).partition(::isWarpSuffix)
        store.write(RuleSetKind.Warp, LearnedRules(exact = exact, suffixes = suffixes))
        val direct = hosts(LearnedRoute.Direct)
        store.write(RuleSetKind.Open, LearnedRules(exact = hosts(LearnedRoute.FailOpen) + direct))
        store.write(RuleSetKind.Direct, LearnedRules(exact = direct))
    }

    private fun isWarpSuffix(domain: String) = PublicSuffixList.bundled.registrableDomain(domain) == domain

    /**
     * The learned state is already stored, so a file that cannot be written only
     * delays it: the next sync writes everything again. Logged once per outage,
     * and never thrown into the probe workers that called it.
     */
    private suspend fun syncKeepingState(): Boolean {
        try {
            syncRuleSets()
            syncFailing = false
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!syncFailing) logger.w(TAG, "learned rule-set write failed, retrying on the next change: ${e.message}")
            syncFailing = true
            return false
        }
    }

    /** A connection left open keeps its old route until it ends, so a failure is logged once per outage and dropped. */
    private suspend fun closeKeepingState(match: HostMatch) {
        try {
            closer.close(match)
            closeFailing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closeFailing) logger.w(TAG, "closing rerouted connections failed: ${e.message}")
            closeFailing = true
        }
    }

    private companion object {
        const val TAG = "LearnedSites"
        const val SUPPRESS_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val RELOAD_SETTLE_MILLIS = 1_000L
        val ROUTED = setOf(LearnedRoute.Warp, LearnedRoute.FailOpen, LearnedRoute.Direct)
        val KEPT = setOf(LearnedRoute.FailOpen, LearnedRoute.Direct)
    }
}
