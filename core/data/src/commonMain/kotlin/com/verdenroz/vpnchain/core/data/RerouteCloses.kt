package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.tunnel.ConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.HostMatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Closes the connections a route move leaves on its old route, once the
 * rule-set write that carries the move has had time to reload.
 *
 * Each move is [queue]d with a sequence number. A write releases the moves
 * it read ([written]); a failed write releases nothing. A render for a
 * connect or reload drops what it read instead ([superseded]), since that
 * redials everything. One close runs at a time.
 */
internal class RerouteCloses(
    private val closer: ConnectionCloser,
    private val logger: Logger,
    private val scope: CoroutineScope,
    timeSource: TimeSource,
) {
    private class Pending(val match: HostMatch, val releasedAt: Duration) {
        // sing-box reloads a rule-set 100 ms after its file goes quiet; a redial before that keeps the old route.
        val notBefore = releasedAt + RELOAD_FLOOR
    }

    private val origin = timeSource.markNow()
    private val lock = Mutex()
    private val queued = mutableMapOf<Long, HostMatch>()
    private val pending = mutableListOf<Pending>()
    private var routedSites = false
    private var job: Job? = null
    private var failing = false

    suspend fun queue(seq: Long, match: HostMatch) = lock.withLock {
        queued[seq] = match
        report()
    }

    suspend fun written(seenSeq: Long, routed: Boolean) = lock.withLock {
        routedSites = routed
        val now = origin.elapsedNow()
        take(seenSeq).forEach { pending += Pending(it, now) }
        if (pending.isNotEmpty() && job == null) job = scope.launch { closeWhenDue() }
        report()
    }

    suspend fun superseded(seenSeq: Long, routed: Boolean) = lock.withLock {
        routedSites = routed
        take(seenSeq)
        pending.clear()
        report()
    }

    suspend fun forget() = lock.withLock {
        queued.clear()
        pending.clear()
        report()
    }

    private fun take(seenSeq: Long): List<HostMatch> {
        val released = queued.keys.filter { it <= seenSeq }.sorted()
        return released.map { queued.remove(it)!! }
    }

    private fun report() = closer.track(routedSites || queued.isNotEmpty() || pending.isNotEmpty())

    /** Waits for the latest floor in the batch, but no longer than [MAX_WAIT] past the oldest move. */
    private suspend fun closeWhenDue() {
        while (true) {
            val wait = lock.withLock {
                if (pending.isEmpty()) {
                    job = null
                    return
                }
                minOf(pending.first().releasedAt + MAX_WAIT, pending.maxOf { it.notBefore }) - origin.elapsedNow()
            }
            delay(wait)
            val ready = lock.withLock {
                val now = origin.elapsedNow()
                pending.filter { it.notBefore <= now }.also { pending.removeAll(it) }.map { it.match }.distinct()
            }
            if (ready.isNotEmpty()) withContext(NonCancellable) { close(ready) }
            lock.withLock { report() }
        }
    }

    /** A connection left open keeps its old route until it ends, so a failure is logged once per outage and dropped. */
    private suspend fun close(matches: List<HostMatch>) {
        try {
            closer.close(matches)
            failing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!failing) logger.w(TAG, "closing rerouted connections failed: ${e.message}")
            failing = true
        }
    }

    private companion object {
        const val TAG = "LearnedSites"
        val RELOAD_FLOOR = 300.milliseconds
        val MAX_WAIT = 3.seconds
    }
}
