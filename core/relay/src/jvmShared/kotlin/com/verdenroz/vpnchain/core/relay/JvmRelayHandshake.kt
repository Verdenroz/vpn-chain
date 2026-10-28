package com.verdenroz.vpnchain.core.relay

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createRelayHandshake(): RelayHandshake = JvmRelayHandshake()

private const val TIMEOUT_MS = 5_000

internal class JvmRelayHandshake : RelayHandshake {

    override suspend fun completes(host: String, port: Int, sni: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = Socket()
                raw.soTimeout = TIMEOUT_MS
                raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, sni, port, true) as SSLSocket).use { it.startHandshake() }
            }.isSuccess
        }
}
