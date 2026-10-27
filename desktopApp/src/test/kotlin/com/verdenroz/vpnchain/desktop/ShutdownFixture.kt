package com.verdenroz.vpnchain.desktop

import java.util.concurrent.CountDownLatch

internal const val FIXTURE_GRACE_MS = 500L

fun main(args: Array<String>) {
    installShutdownWatchdog(graceMs = FIXTURE_GRACE_MS)
    if ("block" in args) Runtime.getRuntime().addShutdownHook(Thread { CountDownLatch(1).await() })
    println("ready")
    Thread.sleep(Long.MAX_VALUE)
}
