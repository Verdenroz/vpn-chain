package com.verdenroz.vpnchain.core.tunnel

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * The bridge sing-box calls into for OS-level operations. It builds the Android
 * TUN from the box's [TunOptions], protects the box's own sockets so they skip
 * the tunnel (no loop), and reports the default network for `auto_detect_interface`.
 */
internal class VpnPlatformInterface(
    private val service: VpnChainService,
) : PlatformInterface {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun openTun(options: TunOptions): Int {
        val builder = service.Builder()
        builder.setMtu(options.mtu)
        builder.setSession("vpn-chain")

        options.inet4Address.readAll().forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.readAll().forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            val routes4 = options.inet4RouteAddress.readAll()
            if (routes4.isEmpty()) builder.addRoute("0.0.0.0", 0)
            else routes4.forEach { builder.addRoute(it.address(), it.prefix()) }

            options.inet6RouteAddress.readAll().forEach { builder.addRoute(it.address(), it.prefix()) }

            runCatching { options.dnsServerAddress.value }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { builder.addDnsServer(it) }

            options.includePackage.readAll().forEach {
                runCatching { builder.addAllowedApplication(it) }
            }
            options.excludePackage.readAll().forEach {
                runCatching { builder.addDisallowedApplication(it) }
            }
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        service.retainTunDescriptor(pfd)
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) throw IllegalStateException("VpnService.protect($fd) failed")
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = service.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(cm, network, listener)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                notify(cm, network, listener, lp)
            override fun onLost(network: Network) =
                listener.updateDefaultInterface("", 0, false, false)
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = service.getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun notify(
        cm: ConnectivityManager,
        network: Network,
        listener: InterfaceUpdateListener,
        linkProperties: LinkProperties? = cm.getLinkProperties(network),
    ) {
        val name = linkProperties?.interfaceName ?: return
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
        listener.updateDefaultInterface(name, index, false, false)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = runCatching {
            JavaNetworkInterface.getNetworkInterfaces().toList().map { nif ->
                LibboxNetworkInterface().apply {
                    name = nif.name
                    index = nif.index
                    mtu = runCatching { nif.mtu }.getOrDefault(-1)
                    type = Libbox.InterfaceTypeOther
                    metered = false
                    addresses = StringList(
                        nif.interfaceAddresses.mapNotNull { addr ->
                            // Link-local IPv6 addresses carry a "%zone" suffix
                            addr.address.hostAddress
                                ?.substringBefore('%')
                                ?.let { "$it/${addr.networkPrefixLength}" }
                        },
                    )
                }
            }
        }.getOrDefault(emptyList())
        return NetworkInterfaceList(interfaces)
    }

    // Unused by our config; provide safe defaults so libbox never NPEs.
    override fun useProcFS(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification) = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator = StringList(emptyList())

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = throw UnsupportedOperationException("process matching not supported")
}
