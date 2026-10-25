package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.ClashApi
import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.tunnel.DestinationFeed
import com.verdenroz.vpnchain.core.warp.probe.ProbeOutcome
import com.verdenroz.vpnchain.core.warp.probe.PublicSuffixList
import com.verdenroz.vpnchain.core.warp.probe.SiteProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the running engine exposes for detection: the probes, and the API to watch. */
internal data class LearningSession(val probe: ProbeProxy, val clashApi: ClashApi?)

/** Null for a config without probe inbounds, or none at all. */
internal fun learningSessionOf(config: String?): LearningSession? = config
    ?.let(ProbeProxy::fromConfig)
    ?.let { LearningSession(it, ClashApi.fromConfig(config)) }

/**
 * Probes each new destination domain once and learns the ones the relay
 * refuses. The relay is probed first, so nothing is sent anywhere the relay does
 * not already see; WARP hears about a domain only after the relay refuses it.
 * Runs only while the chain is up, with at most [WORKERS] probes in flight,
 * and probes only while [gate] is open; names seen meanwhile wait in the queue.
 * Reachable verdicts are kept across restarts, so a relaunch does not re-probe
 * everything the device already visited.
 */
internal class SiteLearner(
    private val feed: DestinationFeed,
    private val prober: SiteProber,
    private val learned: LearnedSitesRepository,
    private val settings: Flow<UserSettings>,
    private val gate: Flow<Boolean>,
    private val sessions: Flow<LearningSession?>,
    private val status: Flow<ChainStatus>,
    private val now: () -> Long,
    scope: CoroutineScope,
) {
    private val reachable = mutableMapOf<String, Long>()
    private val retryUntil = mutableMapOf<String, Long>()
    private val lock = Mutex()
    private var loaded = false
    private var dirty = false

    init {
        scope.launch {
            combine(
                status.map { it.state == TunnelState.Connected }.distinctUntilChanged(),
                settings.map { it.learningActive }.distinctUntilChanged(),
                sessions,
            ) { connected, learning, session -> session.takeIf { connected && learning } }
                .distinctUntilChanged()
                .collectLatest { session -> if (session != null) watch(session) }
        }
    }

    private suspend fun watch(session: LearningSession) = coroutineScope {
        load()
        val hosts = Channel<String>(QUEUE, BufferOverflow.DROP_OLDEST)
        val open = gate.stateIn(this, SharingStarted.Eagerly, false)
        repeat(WORKERS) {
            launch {
                for (host in hosts) {
                    open.first { it }
                    examine(host, session.probe)
                }
            }
        }
        launch { saveWhileDirty() }
        try {
            feed.hosts(session.clashApi).collect { hosts.trySend(it) }
        } finally {
            withContext(NonCancellable) { save() }
        }
    }

    private suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        reachable += learned.reachable()
        loaded = true
    }

    private suspend fun saveWhileDirty() {
        while (true) {
            delay(SAVE_MILLIS)
            save()
        }
    }

    private suspend fun save() {
        val snapshot = lock.withLock {
            if (!dirty) return
            dirty = false
            reachable.entries.sortedByDescending { it.value }.take(KEPT).associate { it.key to it.value }
        }
        learned.saveReachable(snapshot)
    }

    /**
     * WARP learns the registrable domain, since the tail still runs through the
     * relay. A route that leaves the relay is learned for the exact host alone,
     * so one host's verdict never moves its siblings off the chain. With no
     * entry hop only the bare link is left, and that is never probed or routed
     * on its own: the site is offered, and waits for the user to allow it.
     */
    private suspend fun examine(raw: String, probe: ProbeProxy) {
        val host = hostName(raw) ?: return
        val domain = PublicSuffixList.bundled.registrableDomain(host) ?: host
        val viaEntryHop = probe.fallbackPort != 0
        val directOffered = !viaEntryHop && settings.first().failOpenActive
        if (!claim(host, domain, viaEntryHop, directOffered)) return
        val outcome = prober.probe(host, probe)
        if (outcome == ProbeOutcome.Reachable) {
            lock.withLock {
                reachable.makeRoom()
                reachable[domain] = now() + CHECKED_MILLIS
                dirty = true
            }
        }
        when (outcome) {
            is ProbeOutcome.WarpOnly -> learned.learn(domain, LearnedRoute.Warp, outcome.reason)
            is ProbeOutcome.RefusedByBoth ->
                if (directOffered) {
                    learned.learn(host, LearnedRoute.DirectAvailable, outcome.reason)
                } else {
                    learned.learn(domain, LearnedRoute.RefusedByBoth, outcome.reason)
                }
            is ProbeOutcome.FallbackOpen -> learned.learn(host, LearnedRoute.FailOpen, outcome.reason)
            is ProbeOutcome.RefusedEverywhere -> learned.learn(domain, LearnedRoute.RefusedEverywhere, outcome.reason)
            ProbeOutcome.Reachable, ProbeOutcome.Unknown -> Unit
        }
    }

    /** False for a site checked recently, already learned, or paused by the user. */
    private suspend fun claim(host: String, domain: String, viaEntryHop: Boolean, directOffered: Boolean): Boolean {
        if (lock.withLock { checkedRecently(domain) }) return false
        val known = learned.sites.first().filter { it.domain == domain || it.domain == host }
        // A refusal recorded when this session's way out did not exist gets one more look.
        val recheck = known.any {
            (it.route == LearnedRoute.RefusedByBoth && (viaEntryHop || directOffered)) ||
                (it.route == LearnedRoute.DirectAvailable && viaEntryHop)
        }
        if (!recheck && known.isNotEmpty()) return false
        if (learned.isSuppressed(domain) || learned.isSuppressed(host)) return false
        return lock.withLock {
            if (checkedRecently(domain)) return@withLock false
            retryUntil.makeRoom()
            retryUntil[domain] = now() + if (recheck) CHECKED_MILLIS else RETRY_MILLIS
            true
        }
    }

    /** Drops lapsed verdicts once full, then the soonest to lapse, down to a tenth below [KEPT]. */
    private fun MutableMap<String, Long>.makeRoom() {
        if (size < KEPT) return
        values.removeAll { it <= now() }
        if (size < KEPT) return
        entries.sortedBy { it.value }.take(size - KEPT * 9 / 10).forEach { remove(it.key) }
    }

    private fun checkedRecently(domain: String): Boolean =
        (reachable[domain] ?: 0L) > now() || (retryUntil[domain] ?: 0L) > now()

    private companion object {
        const val WORKERS = 2
        const val QUEUE = 256
        const val KEPT = 2_000
        const val SAVE_MILLIS = 5L * 60 * 1000
        const val CHECKED_MILLIS = 12L * 60 * 60 * 1000
        const val RETRY_MILLIS = 5L * 60 * 1000
    }
}

private fun isHostChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

private val LOCAL_SUFFIXES = setOf("local", "lan", "internal", "localhost", "arpa", "home", "invalid")

/** The host as a lowercase name; null for addresses and local names, which are never probed. */
internal fun hostName(host: String): String? {
    val labels = host.lowercase().trimEnd('.').split('.')
    if (labels.size < 2 || labels.any { label -> label.isEmpty() || !label.all(::isHostChar) }) return null
    if (labels.last() in LOCAL_SUFFIXES || labels.last().all { it.isDigit() }) return null
    return labels.joinToString(".")
}
