package com.verdenroz.vpnchain.core.relay

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * A key kept in an owner-only file, created on first use: the private key as PKCS#8 on the first
 * line, the public key as SubjectPublicKeyInfo on the second, both base64. The JDK cannot derive
 * the second from the first, so both are stored.
 */
fun fileManagementKey(file: File): ManagementKey {
    if (!file.exists() || file.length() == 0L) {
        val pair = newSoftwareKeyPair()
        PrivateStorage.ensurePrivateFile(file)
        file.writeText(listOf(pair.private.encoded, pair.public.encoded).joinToString("\n") { Base64.getEncoder().encodeToString(it) })
    }
    val (private, public) = file.readLines().map { Base64.getDecoder().decode(it) }
    return JvmManagementKey(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(private)), public)
}
