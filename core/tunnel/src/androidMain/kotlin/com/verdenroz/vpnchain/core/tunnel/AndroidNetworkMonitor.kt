package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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

        // Nothing is delivered when there is no matching network, so the offline case has to be seeded rather than waited for
        @Suppress("DEPRECATION")
        manager.allNetworks.forEach { network ->
            if (manager.getNetworkCapabilities(network)?.carriesRealTraffic() == true) usable.add(network)
        }
        publish()
        manager.registerNetworkCallback(request, callback)

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()

    /**
     * Tracks the identity of the link the tunnel dials out of, and reports every
     * change of it. Scoped to `NOT_VPN` for the same reason the platform
     * interface is: once our own TUN is up it is itself a network, and counting
     * it would report a change every time the chain came up or went down.
     *
     * The first network seen is not an event — it is the state collection
     * started in, and treating it as a change would fire a probe at every
     * connect.
     */
    override val linkChanges: Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val current = AtomicReference<Network?>(null)

        fun observe(network: Network) {
            val previous = current.getAndSet(network)
            if (previous != null && previous != network) trySend(Unit)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = observe(network)

            // A link we are not on going away says nothing; on API 31+ this only
            // ever fires for the best-matching one, which is the one we are on.
            override fun onLost(network: Network) {
                if (current.compareAndSet(network, null)) trySend(Unit)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        } else {
            manager.registerNetworkCallback(request, callback)
        }

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate()
}

/**
 * Ignores our own tunnel: once the chain is up it is itself a validated
 * network, and counting it would mask the underlying link going away.
 */
private fun NetworkCapabilities.carriesRealTraffic(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
