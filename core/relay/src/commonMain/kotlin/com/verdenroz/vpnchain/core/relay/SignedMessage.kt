package com.verdenroz.vpnchain.core.relay

import okio.ByteString.Companion.toByteString

private const val CONTEXT = "vpn-chain-relay-v1"

/**
 * What a management key signs: the context, the method, the request target exactly as sent, the
 * lowercase hex SHA-256 of the body, the timestamp in unix seconds and the nonce, one per line.
 */
internal fun signedMessage(method: String, target: String, body: ByteArray, timestamp: String, nonce: String) =
    "$CONTEXT\n$method\n$target\n${body.toByteString().sha256().hex()}\n$timestamp\n$nonce"
