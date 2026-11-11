package com.verdenroz.vpnchain.core.tunnel

import java.io.File

/** Packets the physical interfaces have received and sent since boot. */
internal data class LinkCounters(val rx: Long, val tx: Long)

internal enum class StallCause { Chain, Link }

/**
 * Decides whether the chain or the link under it stopped working, from counters
 * sampled before and after the failed probes. The counters are read passively,
 * because a request on the bare link is what the chain exists to prevent.
 *
 * Only a link that took packets and returned none is blamed. Anything less
 * certain blames the chain, which is the verdict that rebuilds it.
 */
internal fun stallCause(before: LinkCounters?, after: LinkCounters?): StallCause =
    if (before != null && after != null && after.tx > before.tx && after.rx == before.rx) {
        StallCause.Link
    } else {
        StallCause.Chain
    }

/**
 * A hold through a silent link has a time limit. Counters cannot tell a dead
 * relay on a quiet network from a dead link, and only a rebuild hands that
 * case to the supervisor's retry and cut-off handling.
 */
internal fun shouldRebuild(cause: StallCause, heldForMs: Long?): Boolean =
    cause == StallCause.Chain || (heldForMs != null && heldForMs >= LINK_HOLD_LIMIT_MS)

internal const val LINK_HOLD_LIMIT_MS = 2 * 60 * 1000L

/** Null where the counters cannot be read or no interface is backed by a device. */
internal fun readLinkCounters(sysNet: File = File("/sys/class/net")): LinkCounters? = runCatching {
    sysNet.listFiles().orEmpty()
        // A tun, bridge or loopback has no device behind it.
        .filter { File(it, "device").exists() }
        .map { LinkCounters(rx = it.packets("rx_packets"), tx = it.packets("tx_packets")) }
        .reduceOrNull { sum, next -> LinkCounters(sum.rx + next.rx, sum.tx + next.tx) }
}.getOrNull()

private fun File.packets(counter: String): Long = File(this, "statistics/$counter").readText().trim().toLong()
