package com.verdenroz.vpnchain.core.tunnel

import io.nekohasekai.libbox.Libbox
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The user's open connections by id, as the service's connections stream reports them. */
internal class LiveConnections {
    private val hosts = ConcurrentHashMap<String, String>()

    fun opened(id: String, host: String) {
        if (host.isNotEmpty()) hosts[id] = host
    }

    fun closed(id: String) {
        hosts.remove(id)
    }

    fun clear() = hosts.clear()

    fun matching(match: HostMatch): List<String> = hosts.filterValues(match::covers).keys.toList()
}

/**
 * Closes connections by id through a standalone libbox command client. Only
 * connections the stream has reported are known, so nothing is closed while
 * detection is not listening.
 */
internal class LibboxConnectionCloser(
    private val live: LiveConnections,
    private val closeConnection: (String) -> Unit = { Libbox.newStandaloneCommandClient().closeConnection(it) },
) : ConnectionCloser {

    /** Each id is forgotten once tried, so one left by an engine that has since been replaced fails only once. */
    override suspend fun close(match: HostMatch) = withContext(Dispatchers.IO) {
        val failure = live.matching(match)
            .map { id -> runCatching { closeConnection(id) }.also { live.closed(id) } }
            .firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null) throw failure
    }
}
