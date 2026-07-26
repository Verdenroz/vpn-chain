package com.verdenroz.vpnchain.core.warp

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WarpPlatformClientTest {

    /** WireGuard and Cloudflare both expect raw 32-byte X25519 halves, base64. */
    @Test
    fun `generates a base64 x25519 keypair of the size wireguard expects`() {
        val keys = createWarpPlatformClient().generateKeyPair()

        val decoder = Base64.getDecoder()
        assertEquals(32, decoder.decode(keys.privateKey).size)
        assertEquals(32, decoder.decode(keys.publicKey).size)
        assertNotEquals(keys.privateKey, keys.publicKey)
    }

    /** A repeated key would hand every install the same WARP identity. */
    @Test
    fun `each call generates a fresh key`() {
        val client = createWarpPlatformClient()

        val first = client.generateKeyPair()
        val second = client.generateKeyPair()

        assertNotEquals(first.privateKey, second.privateKey)
        assertNotEquals(first.publicKey, second.publicKey)
    }

    /** Registration sends the public half — deriving it from the wrong end
     *  would register a key the tunnel can never authenticate with. */
    @Test
    fun `the public half is derived from the private one`() {
        val keys = createWarpPlatformClient().generateKeyPair()

        val derived = com.google.crypto.tink.subtle.X25519
            .publicFromPrivate(Base64.getDecoder().decode(keys.privateKey))
        assertTrue(Base64.getEncoder().encodeToString(derived) == keys.publicKey)
    }
}
