package com.verdenroz.vpnchain.core.tunnel

import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's open connections by id, as the service's connections stream reports
 * them. Each stream gets a generation from [restart]; events from an older one
 * are dropped, since a replaced client can still deliver after it disconnects.
 */
internal class LiveConnections {
    private val hosts = HashMap<String, String>()
    private var generation = 0

    @Synchronized
    fun restart(): Int {
        hosts.clear()
        return ++generation
    }

    @Synchronized
    fun reset(generation: Int) {
        if (generation == this.generation) hosts.clear()
    }

    /** A stream's initial state lists recently closed connections as new ones, with [closedAt] set. */
    @Synchronized
    fun opened(generation: Int, id: String, host: String, closedAt: Long) {
        if (generation == this.generation && host.isNotEmpty() && closedAt == 0L) hosts[id] = host
    }

    @Synchronized
    fun closed(generation: Int, id: String) {
        if (generation == this.generation) hosts.remove(id)
    }

    @Synchronized
    fun forget(id: String) {
        hosts.remove(id)
    }

    @Synchronized
    fun matching(matches: Collection<HostMatch>): List<String> =
        hosts.filterValues { host -> matches.any { it.covers(host) } }.keys.toList()
}

/**
 * Closes connections by id through one standalone libbox command client per
 * batch, which dials the command server for each call. Only connections the
 * stream has reported are known.
 */
internal class LibboxConnectionCloser(
    private val live: LiveConnections,
    private val tracking: (Boolean) -> Unit = {},
    private val newClient: () -> (String) -> Unit = { Libbox.newStandaloneCommandClient()::closeConnection },
) : ConnectionCloser {

    /** Each id is forgotten once tried, so one left by an engine that has since been replaced fails only once. */
    override suspend fun close(matches: Collection<HostMatch>) {
        val ids = live.matching(matches)
        if (ids.isEmpty()) return
        val failure = withContext(Dispatchers.IO) {
            nonFatal {
                val closeConnection = newClient()
                ids.mapNotNull { id -> nonFatal { closeConnection(id) }.also { live.forget(id) } }.firstOrNull()?.let { throw it }
            }
        } ?: return
        // A JNI or Go-side Error is not an Exception, and nothing above this catches anything wider.
        throw failure as? Exception ?: IllegalStateException("libbox could not close a connection", failure)
    }

    override fun track(routedSites: Boolean) = tracking(routedSites)

    private inline fun nonFatal(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: VirtualMachineError) {
        throw e
    } catch (t: Throwable) {
        t
    }
}
