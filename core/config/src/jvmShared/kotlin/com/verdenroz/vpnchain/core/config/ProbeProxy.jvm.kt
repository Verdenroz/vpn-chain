package com.verdenroz.vpnchain.core.config

import java.net.ServerSocket
import java.security.SecureRandom

actual fun newProbeProxy(): ProbeProxy = ProbeProxy(
    relayPort = freeLoopbackPort(),
    warpPort = freeLoopbackPort(),
    fallbackPort = freeLoopbackPort(),
    username = randomHexToken(TOKEN_BYTES),
    password = randomHexToken(TOKEN_BYTES),
)

/**
 * A port free right now. sing-box binds it a moment later, and losing that
 * race costs the listener, not the tunnel.
 */
internal fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

internal fun randomHexToken(bytes: Int): String =
    ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private const val TOKEN_BYTES = 16
