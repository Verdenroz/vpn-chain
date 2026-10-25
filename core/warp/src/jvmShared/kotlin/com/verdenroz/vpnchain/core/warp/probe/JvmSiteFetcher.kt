package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.config.ProbeProxy
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createSiteFetcher(): SiteFetcher = JvmSiteFetcher()

/**
 * CONNECT and TLS by hand: HttpURLConnection authenticates proxies through a
 * JVM-wide Authenticator and won't send credentials on a tunnel by default.
 */
internal class JvmSiteFetcher : SiteFetcher {

    override suspend fun get(host: String, port: Int, proxy: ProbeProxy): ProbeResponse? =
        withContext(Dispatchers.IO) {
            val raw = Socket()
            // Blocking reads ignore cancellation; closing the socket is what ends them.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    raw.close()
                }
            }
            runCatching {
                raw.use {
                    raw.soTimeout = TIMEOUT_MS
                    raw.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
                    tunnel(raw, host, proxy)
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, host, HTTPS_PORT, true) as SSLSocket
                    tls.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.outputStream.write(request(host).encodeToByteArray())
                    tls.outputStream.flush()
                    readCapped(tls)
                }
            }.getOrNull().also { closer.cancel() }
        }

    private fun tunnel(socket: Socket, host: String, proxy: ProbeProxy) {
        val auth = Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password}".encodeToByteArray())
        socket.outputStream.write(
            ("CONNECT $host:$HTTPS_PORT HTTP/1.1\r\nHost: $host:$HTTPS_PORT\r\n" +
                "Proxy-Authorization: Basic $auth\r\n\r\n").encodeToByteArray(),
        )
        socket.outputStream.flush()
        val reply = StringBuilder()
        while (!reply.endsWith("\r\n\r\n")) reply.append(socket.inputStream.read().also { check(it >= 0) }.toChar())
        check(reply.startsWith("HTTP/1.1 200") || reply.startsWith("HTTP/1.0 200"))
    }

    private fun request(host: String) =
        "GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\n" +
            "Accept: text/html,application/xhtml+xml\r\nAccept-Language: en-US,en;q=0.9\r\n" +
            "Accept-Encoding: identity\r\nConnection: close\r\n\r\n"

    private fun readCapped(socket: Socket): ProbeResponse? {
        val buffer = ByteArray(RETAIN_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = socket.inputStream.read(buffer, filled, buffer.size - filled)
            if (read < 0) return ProbeResponse.parse(buffer.decodeToString(0, filled), complete = true)
            filled += read
        }
        return ProbeResponse.parse(buffer.decodeToString(), complete = false)
    }

    private companion object {
        const val HTTPS_PORT = 443
        const val TIMEOUT_MS = 8_000
        const val RETAIN_BYTES = 48 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
    }
}
