package com.verdenroz.vpnchain.core.tunnel

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
internal class ChainProbe(
    /**
     * Probes run here rather than under the caller's job so one that overruns is
     * abandoned instead of awaited. Name resolution obeys neither the connect nor
     * the read timeout, and no JVM call can interrupt a thread blocked in it.
     */
    private val scope: CoroutineScope,
    /**
     * Two independent operators, either of which is proof enough. One host being
     * down or blocked would otherwise read as a dead chain, and the reconnect
     * that follows would tear down a tunnel that was working.
     */
    private val targets: List<String>,
    private val reaches: suspend (url: String, timeoutMs: Int) -> Boolean,
) {
    private class Flight(val result: CompletableDeferred<Boolean>, val job: Job)

    private val lock = Any()
    private var flight: Flight? = null

    /**
     * The bound matters because this runs under a wake lock. Timing out is a
     * "no" — a chain that cannot answer in this long is not carrying traffic.
     * Callers arriving while a probe is still running share it, so abandoned
     * lookups never stack up.
     */
    suspend fun carriesTraffic(timeoutMs: Int = TIMEOUT_MS): Boolean {
        val shared = synchronized(lock) {
            flight?.takeIf { it.job.isActive } ?: launchFlight(timeoutMs).also { flight = it }
        }
        // The connect and read budgets apply in sequence, so one target spends up
        // to twice timeoutMs before the bound has any business firing.
        return withTimeoutOrNull(2 * timeoutMs.toLong() + GRACE_MS) { shared.result.await() } ?: false
    }

    private fun launchFlight(timeoutMs: Int): Flight {
        val result = CompletableDeferred<Boolean>()
        // Concurrent, and the first success wins: awaiting them in order would
        // let one hung target spend the whole budget the other could answer in.
        val job = scope.launch {
            coroutineScope {
                val probes = this
                targets.forEach { target ->
                    launch {
                        if (reaches(target, timeoutMs)) {
                            result.complete(true)
                            probes.cancel()
                        }
                    }
                }
            }
            result.complete(false)
        }
        return Flight(result, job)
    }

    companion object {
        const val TIMEOUT_MS = 5_000

        /** Room for a target's own timeouts to fire before the outer bound does. */
        private const val GRACE_MS = 1_000L

        private val shared = ChainProbe(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
            ),
            ::httpReaches,
        )

        suspend fun carriesTraffic(timeoutMs: Int = TIMEOUT_MS): Boolean = shared.carriesTraffic(timeoutMs)

        private fun httpReaches(url: String, timeoutMs: Int): Boolean = runCatching {
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
    }
}
