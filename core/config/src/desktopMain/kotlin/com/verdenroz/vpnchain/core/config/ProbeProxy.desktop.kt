package com.verdenroz.vpnchain.core.config

import java.net.ServerSocket
import java.security.SecureRandom

actual fun newProbeProxy(): ProbeProxy {
    val random = SecureRandom()
    fun token() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    return ProbeProxy(
        relayPort = ServerSocket(0).use { it.localPort },
        warpPort = ServerSocket(0).use { it.localPort },
        username = token(),
        password = token(),
    )
}
