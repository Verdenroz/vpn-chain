package com.verdenroz.vpnchain.core.warp

import com.google.crypto.tink.subtle.X25519
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createWarpPlatformClient(): WarpPlatformClient = AndroidWarpPlatformClient()

/**
 * Tink rather than the JDK's own XDH: `KeyPairGenerator("XDH")` only arrived
 * on Android 13, and this app supports 8.0 (API 26) upward.
 */
internal class AndroidWarpPlatformClient : WarpPlatformClient {

    override fun generateKeyPair(): WarpKeyPair {
        val privateKey = X25519.generatePrivateKey()
        val encoder = Base64.getEncoder()
        return WarpKeyPair(
            privateKey = encoder.encodeToString(privateKey),
            publicKey = encoder.encodeToString(X25519.publicFromPrivate(privateKey)),
        )
    }

    override suspend fun post(url: String, body: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                // The API answers only clients that identify as the WARP app.
                setRequestProperty("CF-Client-Version", CLIENT_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val CLIENT_VERSION = "a-6.10-2158"
        const val USER_AGENT = "okhttp/3.12.1"
        const val TIMEOUT_MS = 15_000
    }
}
