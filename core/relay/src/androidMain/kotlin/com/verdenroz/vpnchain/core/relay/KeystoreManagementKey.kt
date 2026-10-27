package com.verdenroz.vpnchain.core.relay

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

private const val ALIAS = "vpnchain-relay-management"

/** A Keystore key, in StrongBox where the device has one. It is generated on first use. */
fun keystoreManagementKey(): ManagementKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!store.containsAlias(ALIAS)) generate()
    return JvmManagementKey(
        privateKey = store.getKey(ALIAS, null) as PrivateKey,
        publicKey = store.getCertificate(ALIAS).publicKey.encoded,
    )
}

private fun generate() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            return generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
        }
    }
    generate(strongBox = false)
}

private fun generate(strongBox: Boolean) {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
        .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
        initialize(spec)
        generateKeyPair()
    }
}
