package com.verdenroz.vpnchain.core.warp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shaped after a real registration response, with the account block trimmed. */
private const val RESPONSE = """
{
  "id": "688c87d9-2f1e-4dac-ad71-3cc3bc9cfb7f",
  "type": "a",
  "token": "d5f45641-715b-4dcd-9f82-837b4ca6216e",
  "warp_enabled": false,
  "account": { "account_type": "free", "license": "e0H1Q7S2-F5fJ92X1-8qR5M3b1" },
  "config": {
    "client_id": "dZaA",
    "peers": [
      {
        "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
        "endpoint": { "v4": "8.47.69.2:0", "host": "engage.cloudflareclient.com:2408" }
      }
    ],
    "interface": {
      "addresses": { "v4": "172.16.0.2", "v6": "2606:4700:110:8e93:94a6:5ae8:ae7f:b110" }
    }
  }
}
"""

class WarpRegistrationTest {

    @Test
    fun `parses the peer key and both interface addresses`() {
        val result = WarpRegistration.parse(RESPONSE)

        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", result?.peerPublicKey)
        assertEquals("172.16.0.2", result?.addressV4)
        assertEquals("2606:4700:110:8e93:94a6:5ae8:ae7f:b110", result?.addressV6)
    }

    /** Cloudflare hands back bare addresses; WireGuard needs host prefixes, and
     *  anything wider would claim to route traffic that isn't ours. */
    @Test
    fun `the exit carries host prefixes and the time it was registered`() {
        val exit = WarpRegistration.parse(RESPONSE)?.toExit(privateKey = "priv", nowMillis = 1_700L)

        assertEquals("172.16.0.2/32", exit?.addressV4)
        assertEquals("2606:4700:110:8e93:94a6:5ae8:ae7f:b110/128", exit?.addressV6)
        assertEquals("priv", exit?.privateKey)
        assertEquals(1_700L, exit?.registeredAtMillis)
    }

    @Test
    fun `a response missing the interface addresses is refused, not half-read`() {
        val withoutAddresses = """{"id":"x","token":"y","config":{"peers":[{"public_key":"k"}]}}"""

        assertNull(WarpRegistration.parse(withoutAddresses))
    }

    @Test
    fun `a response with no peer is refused`() {
        val withoutPeer =
            """{"id":"x","token":"y","config":{"peers":[],"interface":{"addresses":{"v4":"1","v6":"2"}}}}"""

        assertNull(WarpRegistration.parse(withoutPeer))
    }

    @Test
    fun `garbage in is null out rather than a thrown parse error`() {
        assertNull(WarpRegistration.parse("not json at all"))
        assertNull(WarpRegistration.parse(""))
    }

    @Test
    fun `the request offers the public key and accepts the terms`() {
        val body = WarpRegistration.requestBody("PUBLIC_KEY_B64")

        assertTrue("\"key\":\"PUBLIC_KEY_B64\"" in body, body)
        assertTrue("\"tos\":" in body, body)
    }
}
