package com.verdenroz.vpnchain.core.tunnel

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks whether the chain actually carries traffic, end to end.
 *
 * Nothing else on Android can answer that. libbox's status stream is a loopback
 * socket, so it stays healthy while the entry hop's UDP socket sits on a network
 * that went away — which is the whole "connected, zero bytes" failure. This app's
 * own sockets are not protected, so a plain request here leaves through the TUN
 * and exercises the entire path: DNS through the chain, the entry hop, the relay.
 */
internal object ChainProbe {

    /**
     * Two independent operators, either of which is proof enough. One host being
     * down or blocked would otherwise read as a dead chain, and the reconnect
     * that follows would tear down a tunnel that was working.
     */
    private val TARGETS = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
    )

    /**
     * Probes run here rather than under the caller's job so one that overruns is
     * abandoned instead of awaited. Name resolution obeys neither the connect nor
     * the read timeout, and no JVM call can interrupt a thread blocked in it.
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The bound matters because this runs under a wake lock. Timing out is a
     * "no" — a chain that cannot answer in this long is not carrying traffic.
     */
    suspend fun carriesTraffic(timeoutMs: Int = TIMEOUT_MS): Boolean {
        val reached = CompletableDeferred<Boolean>()
        // Concurrent, and the first success wins: awaiting them in order would
        // let one hung target spend the whole budget the other could answer in.
        val probes = TARGETS.map { target ->
            probeScope.launch { if (reaches(target, timeoutMs)) reached.complete(true) }
        }
        probeScope.launch {
            probes.joinAll()
            reached.complete(false)
        }
        // The connect and read budgets apply in sequence, so one target spends up
        // to twice timeoutMs before the bound has any business firing.
        return withTimeoutOrNull(2 * timeoutMs.toLong() + GRACE_MS) { reached.await() } ?: false
    }

    private fun reaches(url: String, timeoutMs: Int): Boolean = runCatching {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Connection", "close")
        try {
            // Any answer at all proves the path: a 204 is what these return, but
            // a redirect or an error page still had to travel the whole chain.
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    const val TIMEOUT_MS = 5_000

    /** Room for a target's own timeouts to fire before the outer bound does. */
    private const val GRACE_MS = 1_000L
}
