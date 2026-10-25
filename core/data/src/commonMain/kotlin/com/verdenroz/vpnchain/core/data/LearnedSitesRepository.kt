package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The sites detection has found, and the rule-set files that carry them to sing-box. */
interface LearnedSitesRepository {
    val sites: Flow<List<LearnedSite>>

    suspend fun isSuppressed(domain: String): Boolean

    /** A suppressed domain is left alone. */
    suspend fun learn(domain: String, route: LearnedRoute, reason: String)

    /** Drops the entry and keeps it from being learned again for a week. */
    suspend fun remove(domain: String)

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

    override suspend fun learn(domain: String, route: LearnedRoute, reason: String) {
        if (isSuppressed(domain)) return
        preferences.putLearnedSite(LearnedSite(domain, reason, now(), route))
        syncRuleSets()
    }

    override suspend fun remove(domain: String) {
        preferences.removeLearnedSite(domain, suppressUntilMillis = now() + SUPPRESS_MILLIS)
        syncRuleSets()
    }

    override suspend fun syncRuleSets() {
        val learned = preferences.learnedSites.first()
        store.write(LearnedRoute.Warp, learned.filter { it.route == LearnedRoute.Warp }.map { it.domain })
    }

    private companion object {
        const val SUPPRESS_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
