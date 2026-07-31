package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.os.PowerManager

/**
 * Holds the CPU on across work that must not be suspended half-done.
 *
 * Bringing the chain up and deciding whether it carries traffic are both that:
 * Doze stops the clock between the handshake and the first packet, and the
 * probe that follows times out against a device that was simply asleep — read
 * as a dead chain, and torn down for it. Backoff waits are deliberately left
 * outside, because what ends them early is a network callback, which wakes the
 * device on its own.
 */
internal class AndroidWakeGuard(context: Context) {

    private val power = context.applicationContext.getSystemService(PowerManager::class.java)

    suspend fun <T> awake(block: suspend () -> T): T {
        // Timeout as a backstop, not as the plan: the finally below is the
        // normal release, and this only covers a process torn down mid-attempt.
        val lock = runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    private companion object {
        const val TAG = "vpn-chain:tunnel"
        const val MAX_HOLD_MS = 2 * 60 * 1000L
    }
}
