package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Coarse "is there a usable network right now" signal.
 *
 * Exists so a dropped chain retries the moment connectivity returns — waiting
 * out a 30s backoff after the Wi-Fi is already back is the difference between
 * a blip and a visibly broken tunnel. Deliberately not a reachability test:
 * whether the *chain* works is what connecting decides.
 */
interface NetworkMonitor {
    /** Emits the current state on collection, then on every change. */
    val online: Flow<Boolean>

    /**
     * Emits whenever the link underneath the tunnel changes identity — a Wi-Fi
     * to cellular handover, a new cell attach, a different Wi-Fi.
     *
     * [online] cannot express this: both links are usable across a handover, so
     * it never leaves `true` while every socket bound to the old one silently
     * black-holes. This is what tells a live chain to re-examine itself, and
     * what ends a reconnect backoff early when nothing else moved.
     */
    val linkChanges: Flow<Unit> get() = emptyFlow()
}
