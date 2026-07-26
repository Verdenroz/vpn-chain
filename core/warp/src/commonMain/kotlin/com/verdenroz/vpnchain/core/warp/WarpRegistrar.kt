package com.verdenroz.vpnchain.core.warp

import com.verdenroz.vpnchain.core.model.WarpExit

/** An X25519 keypair, both halves base64 as WireGuard and Cloudflare exchange them. */
data class WarpKeyPair(val privateKey: String, val publicKey: String)

/**
 * The parts of registration that aren't pure: key generation and the one
 * HTTPS call. Split out so the request/response handling in
 * [WarpRegistration] can be tested without a network or a platform.
 */
interface WarpPlatformClient {
    fun generateKeyPair(): WarpKeyPair

    /** @return the response body, or null on any transport or non-2xx failure. */
    suspend fun post(url: String, body: String): String?
}

expect fun createWarpPlatformClient(): WarpPlatformClient

/**
 * Obtains WARP credentials from Cloudflare's free registration API — the same
 * call `wgcf` makes, so no external tool has to be installed to use the exit.
 */
interface WarpRegistrar {
    suspend fun register(nowMillis: Long): Result<WarpExit>
}

internal class DefaultWarpRegistrar(
    private val client: WarpPlatformClient,
) : WarpRegistrar {

    /**
     * Runs before the tunnel exists, so it goes out over whatever path is
     * currently up. That is a plain HTTPS call to Cloudflare — unremarkable
     * traffic — but it is the one part of the chain that isn't tunnelled, and
     * a network that blocks it simply leaves the exit unregistered.
     *
     * `wgcf` follows registration with a PATCH setting `warp_enabled`; the
     * endpoint routes without it (verified: same egress address, same sites
     * reachable), and HttpURLConnection can't issue a PATCH on Android, so
     * this stops at the one call that matters.
     */
    override suspend fun register(nowMillis: Long): Result<WarpExit> = runCatching {
        val keys = client.generateKeyPair()
        val response = client.post(REGISTER_URL, WarpRegistration.requestBody(keys.publicKey))
            ?: error("Cloudflare did not answer the WARP registration")
        val registration = WarpRegistration.parse(response)
            ?: error("WARP registration response was not in the expected shape")
        registration.toExit(keys.privateKey, nowMillis)
    }

    private companion object {
        const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    }
}
