package com.verdenroz.vpnchain.core.relay

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Signs with any JCA private key, which includes one that lives in the Android Keystore. */
internal class JvmManagementKey(
    private val privateKey: PrivateKey,
    override val publicKey: ByteArray,
) : ManagementKey {

    override suspend fun sign(message: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }
}

internal fun newSoftwareKeyPair() =
    KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
