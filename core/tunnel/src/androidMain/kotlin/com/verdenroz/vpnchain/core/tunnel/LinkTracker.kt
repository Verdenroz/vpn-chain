package com.verdenroz.vpnchain.core.tunnel

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides which network callbacks are link changes. The first link seen is the
 * state collection started in, so it is not one; a link returning after an
 * outage is, because every socket bound to the old one is dead.
 */
internal class LinkTracker<T : Any> {
    private val current = AtomicReference<T?>(null)
    private val outage = AtomicBoolean(false)

    /** @return whether [network] replaces the link we were on, or restores one after an outage. */
    fun available(network: T): Boolean {
        val previous = current.getAndSet(network)
        return if (previous == null) outage.getAndSet(false) else previous != network
    }

    /** @return whether [network] was the link we were on. */
    fun lost(network: T): Boolean {
        val wasCurrent = current.compareAndSet(network, null)
        if (wasCurrent) outage.set(true)
        return wasCurrent
    }
}
