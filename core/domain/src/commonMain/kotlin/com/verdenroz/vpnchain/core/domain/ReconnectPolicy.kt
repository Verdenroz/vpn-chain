package com.verdenroz.vpnchain.core.domain

/**
 * Backoff schedule for reconnect attempts after an unexpected drop.
 *
 * Capped rather than unbounded: a tunnel that has been down for hours should
 * still retry on a predictable cadence, because the thing that fixes it is
 * usually the network coming back, not the app waiting longer.
 */
object ReconnectPolicy {

    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /**
     * Wait before [attempt], where the first retry after a drop is attempt 1.
     *
     * @throws IllegalArgumentException if [attempt] is not positive.
     */
    fun delayForAttemptMillis(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based, got $attempt" }
        // Clamp the shift first: `BASE shl 64` wraps around to a negative delay,
        // which `delay()` reads as "don't wait" — a hot reconnect loop.
        val steps = (attempt - 1).coerceAtMost(MAX_SHIFT)
        return (BASE_DELAY_MS shl steps).coerceAtMost(MAX_DELAY_MS)
    }

    private const val MAX_SHIFT = 16
}
