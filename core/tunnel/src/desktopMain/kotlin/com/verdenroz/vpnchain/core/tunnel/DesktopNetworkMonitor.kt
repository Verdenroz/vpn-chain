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
 * backoff, not to be the thing that notices first.
 */
internal class DesktopNetworkMonitor(
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : NetworkMonitor {

    override val online: Flow<Boolean> = flow {
        while (true) {
            emit(hasUsableInterface())
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun hasUsableInterface(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { iface ->
            iface.isUp && !iface.isLoopback && !iface.isOurTunnel() && iface.hasRoutableAddress()
        }
    }.getOrDefault(false)

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

private fun NetworkInterface.hasRoutableAddress(): Boolean =
    inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }

// The literal interface the ProtonVPN desktop app creates — the only external
// VPN client detected here, because it is the only one with a fixed name.
private const val ENTRY_APP_INTERFACE = "proton0"
private const val TUN_ADDRESS = "10.19.19.1"
