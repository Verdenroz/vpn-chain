package com.verdenroz.vpnchain.core.tunnel

/**
 * Decides which network callbacks are link changes. The first link seen is the
 * state collection started in, so it is not one; a link returning after an
 * outage is, because every socket bound to the old one is dead.
 *
 * Not thread-safe: a registered callback's methods are delivered on one thread.
 */
internal class LinkTracker<T : Any> {
    private var current: T? = null
    private var outage = false

    /** @return whether [network] replaces the link we were on, or restores one after an outage. */
    fun available(network: T): Boolean {
        val previous = current
        current = network
        if (previous != null) return previous != network
        val restored = outage
        outage = false
        return restored
    }

    /** @return whether [network] was the link we were on. */
    fun lost(network: T): Boolean {
        if (current != network) return false
        current = null
        outage = true
        return true
    }
}
