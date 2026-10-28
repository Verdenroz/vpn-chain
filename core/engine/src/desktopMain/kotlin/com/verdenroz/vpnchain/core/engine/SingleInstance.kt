package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Takes the [EngineLock], or asks the instance holding it to show its window.
 * The holder binds the control socket a moment after it locks, so the ask
 * retries for [patience] before the unreachable-engine error escapes.
 * Returns null once the running instance has been asked to show.
 */
fun claimOrHandOff(
    paths: EnginePaths,
    appVersion: String,
    logger: Logger,
    patience: Duration = 10.seconds,
): EngineLock? {
    EngineLock.tryAcquire(paths.lock)?.let { return it }
    val deadline = System.nanoTime() + patience.inWholeNanoseconds
    while (true) {
        try {
            EngineClient.connect(paths.socket, appVersion).use { it.call(Request.Show) }
            break
        } catch (e: EngineUnavailableException) {
            if (System.nanoTime() > deadline) throw e
            Thread.sleep(RETRY_PAUSE_MS)
        }
    }
    logger.i(TAG, "vpn-chain is already running; asked it to show its window")
    return null
}

private const val TAG = "SingleInstance"
private const val RETRY_PAUSE_MS = 100L
