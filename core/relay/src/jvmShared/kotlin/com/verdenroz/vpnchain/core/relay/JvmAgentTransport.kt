package com.verdenroz.vpnchain.core.relay

import com.verdenroz.vpnchain.core.config.ProbeProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createAgentTransport(): AgentTransport = JvmAgentTransport()

actual fun newNonce(): String =
    ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private const val NONCE_BYTES = 16

/**
 * CONNECT and HTTP by hand, as the site fetcher does for TLS: the request line, headers and body
 * go out byte for byte as signed, which an HTTP client library would not promise.
 */
internal class JvmAgentTransport : AgentTransport {

    override suspend fun send(proxy: ProbeProxy, request: AgentRequest): AgentResponse =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            // Blocking reads ignore cancellation; closing the socket is what ends them.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    socket.close()
                }
            }
            try {
                socket.use {
                    socket.soTimeout = TIMEOUT_MS
                    socket.connect(InetSocketAddress("127.0.0.1", proxy.relayPort), TIMEOUT_MS)
                    tunnel(socket, proxy)
                    socket.outputStream.write(head(request) + request.body)
                    socket.outputStream.flush()
                    parse(readCapped(socket))
                }
            } catch (e: IOException) {
                throw AgentUnreachable("The relay did not answer.", e)
            } finally {
                closer.cancel()
            }
        }

    private fun tunnel(socket: Socket, proxy: ProbeProxy) {
        val auth = Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password}".encodeToByteArray())
        socket.outputStream.write(
            ("CONNECT $AGENT_HOST:$AGENT_PORT HTTP/1.1\r\nHost: $AGENT_HOST:$AGENT_PORT\r\n" +
                "Proxy-Authorization: Basic $auth\r\n\r\n").encodeToByteArray(),
        )
        socket.outputStream.flush()
        val reply = StringBuilder()
        while (!reply.endsWith("\r\n\r\n")) reply.append(socket.inputStream.read().also { if (it < 0) throw IOException("closed") }.toChar())
        if (!reply.startsWith("HTTP/1.1 200") && !reply.startsWith("HTTP/1.0 200")) throw IOException("the probe refused the tunnel")
    }

    private fun head(request: AgentRequest): ByteArray {
        val headers = request.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        return ("${request.method} ${request.target} HTTP/1.1\r\nHost: $AGENT_HOST\r\n" +
            "Content-Length: ${request.body.size}\r\n$headers" +
            "Accept: application/json\r\nConnection: close\r\n\r\n").encodeToByteArray()
    }

    private fun readCapped(socket: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < MAX_RESPONSE_BYTES) {
            val read = socket.inputStream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parse(raw: ByteArray): AgentResponse {
        val text = raw.toString(Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0) throw IOException("not an HTTP response")
        val lines = text.substring(0, split).split("\r\n")
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP response")
        val date = lines.drop(1)
            .firstOrNull { it.startsWith("date:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return AgentResponse(
            status = status,
            body = raw.decodeToString(split + 4, raw.size),
            serverTime = date?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull() },
        )
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
