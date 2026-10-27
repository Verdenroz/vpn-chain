package com.verdenroz.vpnchain.core.relay

import okio.ByteString.Companion.toByteString

/** The P-256 key this device signs relay requests with. The private half never leaves the device. */
interface ManagementKey {
    /** SubjectPublicKeyInfo DER. */
    val publicKey: ByteArray

    /** An ASN.1 DER ECDSA signature over SHA-256 of [message]. */
    suspend fun sign(message: ByteArray): ByteArray
}

private const val KEY_ID_BYTES = 8

/** How relayd names a key: the first 8 bytes of the SHA-256 of its public key, in hex. */
val ManagementKey.id: String
    get() = publicKey.toByteString().sha256().hex().take(KEY_ID_BYTES * 2)
