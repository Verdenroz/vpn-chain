package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.NetworkInterface

/**
 * Desktop has no portable connectivity callback, so this polls the interface
 * list. The interval is coarse on purpose — it only needs to beat the reconnect
 * backoff, not to be the thing that notices first. The same poll reports a link
 * that changed identity and a machine that slept.
 */
internal class DesktopNetworkMonitor(
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : NetworkMonitor {

    override val online: Flow<Boolean> = flow {
        while (true) {
            emit(usableLinks().isNotEmpty())
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override val linkChanges: Flow<Unit> = flow {
        var links = usableLinks()
        // Wall clock: the monotonic one stops while the machine sleeps.
        var polledAt = System.currentTimeMillis()
        while (true) {
            delay(pollIntervalMs)
            val current = usableLinks()
            val now = System.currentTimeMillis()
            if (linkMoved(links, current, elapsedMs = now - polledAt, pollMs = pollIntervalMs)) emit(Unit)
            links = current
            polledAt = now
        }
    }.flowOn(Dispatchers.IO)

    /** Each usable interface with its routable addresses, which together identify the link. */
    private fun usableLinks(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isOurTunnel() }
            .flatMap { iface -> iface.routableAddresses().map { "${iface.name}/$it" } }
            .toSet()
    }.getOrDefault(emptySet())

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
    }
}

/**
 * Our own tunnel interfaces are up precisely when the chain is working, so
 * counting them would report "online" during the outage we're trying to detect.
 */
private fun NetworkInterface.isOurTunnel(): Boolean =
    name == ENTRY_APP_INTERFACE ||
        inetAddresses.asSequence().any { it.hostAddress == TUN_ADDRESS }

private fun NetworkInterface.routableAddresses(): Sequence<String> =
    inetAddresses.asSequence().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { it.hostAddress }

// The literal interface the ProtonVPN desktop app creates — the only external
// VPN client detected here, because it is the only one with a fixed name.
private const val ENTRY_APP_INTERFACE = "proton0"
private const val TUN_ADDRESS = "10.19.19.1"
