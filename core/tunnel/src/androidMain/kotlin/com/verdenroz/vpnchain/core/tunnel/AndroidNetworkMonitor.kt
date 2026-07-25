package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap

/**
 * Connectivity from [ConnectivityManager]'s callbacks rather than a poll, so a
 * Wi-Fi/cellular handover is seen immediately.
 */
internal class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {

    override val online: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Callbacks arrive on a binder thread; the set is read from the same
        // callbacks but published to a coroutine, so it has to be concurrent.
        val usable = ConcurrentHashMap.newKeySet<Network>()

        fun publish() {
            trySend(usable.isNotEmpty())
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (capabilities.carriesRealTraffic()) usable.add(network) else usable.remove(network)
                publish()
            }

            override fun onLost(network: Network) {
                usable.remove(network)
                publish()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Nothing is delivered when there is no matching network, so the
        // offline case has to be seeded rather than waited for.
        publish()
        manager.registerNetworkCallback(request, callback)

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}

/**
 * Ignores our own tunnel: once the chain is up it is itself a validated
 * network, and counting it would mask the underlying link going away.
 */
private fun NetworkCapabilities.carriesRealTraffic(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
