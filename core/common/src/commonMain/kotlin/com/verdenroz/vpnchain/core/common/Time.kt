package com.verdenroz.vpnchain.core.common

/** Wall-clock milliseconds; both targets are JVM-based so this is System.currentTimeMillis. */
expect fun currentTimeMillis(): Long
