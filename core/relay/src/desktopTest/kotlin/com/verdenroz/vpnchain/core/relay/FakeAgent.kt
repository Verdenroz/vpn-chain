package com.verdenroz.vpnchain.core.relay

import com.verdenroz.vpnchain.core.config.ProbeProxy
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

internal class Seen(
    val method: String,
    val target: String,
    val body: String,
    val headers: Map<String, String>,
    val connect: String,
    val proxyAuth: String?,
)

internal class Reply(val status: Int, val body: String)

/**
 * A sing-box probe inbound and the relayd agent behind it, in one loopback socket. It accepts
 * CONNECT with the probe credentials, then applies relayd's signature rules independently of the
 * client: the five-line message, a 120 second window, 16 to 64 character nonces never seen twice.
 */
internal class FakeAgent(
    private val keys: Map<String, ByteArray>,
    private val clock: () -> Long,
    private val handler: (Seen, String?) -> Reply,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val nonces = mutableSetOf<Pair<String, String>>()
    val seen = mutableListOf<Seen>()
    val proxy = ProbeProxy(relayPort = server.localPort, warpPort = 0, fallbackPort = 0, username = "probe", password = "secret")

    init {
        Thread { while (!server.isClosed) runCatching { serve(server.accept()) } }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val connectHead = head(input)
        val connect = connectHead.first().split(' ')[1]
        val proxyAuth = connectHead.firstOrNull { it.startsWith("Proxy-Authorization:") }?.substringAfter(": ")
        socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
        val lines = head(input)
        val (method, target) = lines.first().split(' ').let { it[0] to it[1] }
        val headers = lines.drop(1).associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        val body = String(input.readNBytes(headers.getValue("Content-Length").toInt()))
        val request = Seen(method, target, body, headers, connect, proxyAuth)
        synchronized(seen) { seen += request }
        val reply = authenticate(request)
        val principal = if (reply == null) headers["X-Key-Id"] else null
        val answer = reply ?: handler(request, principal)
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(clock()).atOffset(ZoneOffset.UTC))
        socket.getOutputStream().write(
            ("HTTP/1.1 ${answer.status} X\r\nDate: $date\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${answer.body.toByteArray().size}\r\nConnection: close\r\n\r\n${answer.body}").toByteArray(),
        )
    }

    private fun head(input: BufferedInputStream): List<String> {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) text.append(input.read().also { check(it >= 0) }.toChar())
        return text.toString().trimEnd().split("\r\n")
    }

    /** Null when the request is signed correctly, or needs no signature. */
    private fun authenticate(request: Seen): Reply? {
        if (request.target == "/v1/enroll") return null
        val deny = Reply(401, """{"error":"unauthorized"}""")
        val h = request.headers
        val keyId = h["X-Key-Id"] ?: return deny
        val timestamp = h["X-Timestamp"] ?: return deny
        val nonce = h["X-Nonce"] ?: return deny
        val signature = h["X-Signature"] ?: return deny
        val spki = keys[keyId] ?: return deny
        val sent = timestamp.toLongOrNull() ?: return deny
        if (kotlin.math.abs(sent - clock()) > 120 || nonce.length !in 16..64) return deny
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(request.body.toByteArray()).joinToString("") { "%02x".format(it) }
        val message = "vpn-chain-relay-v1\n${request.method}\n${request.target}\n$digest\n$timestamp\n$nonce"
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
            update(message.toByteArray())
            runCatching { verify(Base64.getDecoder().decode(signature)) }.getOrDefault(false)
        }
        if (!valid) return deny
        return if (synchronized(nonces) { nonces.add(keyId to nonce) }) null else deny
    }

    override fun close() = server.close()
}
