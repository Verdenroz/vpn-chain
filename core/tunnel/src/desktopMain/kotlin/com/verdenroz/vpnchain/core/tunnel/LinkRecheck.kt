package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How far past its interval a poll must land before the gap is read as the machine having slept. */
internal const val WAKE_GAP_MS = 10_000L

/**
 * Whether the link under the chain changed between two polls: a different
 * interface or address, a link that came back, or a sleep in between. A link
 * that went away is not a move, because there is nothing to check a chain on.
 */
internal fun linkMoved(previous: Set<String>, current: Set<String>, elapsedMs: Long, pollMs: Long): Boolean =
    current.isNotEmpty() && (current != previous || elapsedMs > pollMs + WAKE_GAP_MS)

/**
 * Waits for the next health check: the full interval, or sooner when the link
 * moved. A moved link gets [settleMs] first, because sing-box needs a moment to
 * rebind and a check that beats it would rebuild a chain about to recover.
 */
internal suspend fun awaitNextCheck(recheck: ReceiveChannel<Unit>, intervalMs: Long, settleMs: Long) {
    if (withTimeoutOrNull(intervalMs) { recheck.receive() } != null) delay(settleMs)
}
