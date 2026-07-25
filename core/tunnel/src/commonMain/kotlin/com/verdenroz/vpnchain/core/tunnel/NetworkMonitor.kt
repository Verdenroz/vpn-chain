package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.flow.Flow

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
}
