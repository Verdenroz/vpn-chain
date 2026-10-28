package com.verdenroz.vpnchain.desktop

import kotlin.concurrent.thread

internal const val SHUTDOWN_GRACE_MS = 3_000L
internal const val FORCED_EXIT_CODE = 124

/**
 * Halts the JVM if a shutdown, SIGTERM or Quit, has not finished [graceMs] after it began.
 * Nothing is torn down: a signal is not a Disconnect, so an armed kill switch stays armed
 * and the relay keeps running, as after a crash.
 */
internal fun installShutdownWatchdog(
    graceMs: Long = SHUTDOWN_GRACE_MS,
    halt: (Int) -> Unit = Runtime.getRuntime()::halt,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            thread(isDaemon = true) {
                Thread.sleep(graceMs)
                halt(FORCED_EXIT_CODE)
            }
        },
    )
}
