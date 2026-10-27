package com.verdenroz.vpnchain.core.tunnel

/** The names a learned route reaches: [host] alone, or with [subdomains] every name under it as well. */
data class HostMatch(val host: String, val subdomains: Boolean) {
    fun covers(name: String): Boolean {
        val lower = name.lowercase().trimEnd('.')
        return lower == host || (subdomains && lower.endsWith(".$host"))
    }
}

/**
 * Closes the engine's open connections to the names a route change moved, so
 * the client redials them under the rules now loaded. A route applies only when
 * a connection opens, and browsers keep reusing pooled connections otherwise.
 * The app's probe connections are never closed.
 */
interface ConnectionCloser {
    /** Closes every connection any of [matches] covers. An engine that is down has nothing to close; one that cannot be reached throws. */
    suspend fun close(matches: Collection<HostMatch>)

    /**
     * Whether a later [close] could have work: a learned site is routed, or a close is pending.
     * An engine that has to watch its connections to close them can skip that while this is false.
     */
    fun track(wanted: Boolean) = Unit
}
