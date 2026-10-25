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
 * race fails the whole start (see [isLostPortRace]).
 */
internal fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

internal fun randomHexToken(bytes: Int): String =
    ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private const val TOKEN_BYTES = 16

/**
 * Whether sing-box's start failure is a loopback port this app picked being
 * taken before sing-box bound it, rather than [fixedPort] (the user's proxy
 * port) or anything else. Only then does rendering again with fresh ports help.
 */
fun isLostPortRace(failure: String, fixedPort: Int? = null): Boolean =
    LOOPBACK_BIND_IN_USE.findAll(failure).any { it.groupValues[1].toInt() != fixedPort }

private val LOOPBACK_BIND_IN_USE = Regex("listen tcp 127\\.0\\.0\\.1:(\\d+): bind: address already in use")
