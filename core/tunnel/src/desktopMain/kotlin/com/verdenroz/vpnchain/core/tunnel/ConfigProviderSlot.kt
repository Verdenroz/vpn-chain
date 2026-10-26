package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The repository's config provider. It is installed after the controller has
 * already begun adopting a running relay at launch, so adoption waits for it
 * briefly instead of missing it.
 */
internal class ConfigProviderSlot(private val waitMillis: Long = DEFAULT_WAIT_MILLIS) {
    @Volatile
    var current: (suspend () -> String?)? = null
        private set
    private val installed = CompletableDeferred<Unit>()

    fun install(provider: suspend () -> String?) {
        current = provider
        installed.complete(Unit)
    }

    /**
     * The config to start a stale adopted relay again with: the one [start] was
     * handed, or else a fresh render. A start never re-renders over its caller.
     */
    suspend fun configForStaleAdopt(startConfig: String?): String? =
        startConfig ?: (current ?: withTimeoutOrNull(waitMillis) { installed.await(); current })?.invoke()

    private companion object {
        const val DEFAULT_WAIT_MILLIS = 5_000L
    }
}
