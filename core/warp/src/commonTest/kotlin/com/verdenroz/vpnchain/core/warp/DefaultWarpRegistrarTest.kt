package com.verdenroz.vpnchain.core.warp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val OK_RESPONSE = """
{"id":"i","token":"t","config":{"peers":[{"public_key":"PEER"}],
 "interface":{"addresses":{"v4":"172.16.0.9","v6":"2606:4700::9"}}}}
"""

private class FakeClient(
    private val response: String?,
    val keys: WarpKeyPair = WarpKeyPair(privateKey = "PRIV", publicKey = "PUB"),
) : WarpPlatformClient {
    var postedBody: String? = null

    override fun generateKeyPair() = keys
    override suspend fun post(url: String, body: String): String? {
        postedBody = body
        return response
    }
}

class DefaultWarpRegistrarTest {

    @Test
    fun `registers with the generated public key and keeps the private half`() = runTest {
        val client = FakeClient(OK_RESPONSE)

        val exit = DefaultWarpRegistrar(client).register(nowMillis = 42L).getOrNull()

        assertNotNull(exit)
        assertTrue("\"key\":\"PUB\"" in (client.postedBody ?: ""), client.postedBody ?: "")
        assertEquals("PRIV", exit.privateKey)
        assertEquals("PEER", exit.peerPublicKey)
        assertEquals("172.16.0.9/32", exit.addressV4)
        assertEquals(42L, exit.registeredAtMillis)
    }

    /** The anycast literal, so the tail needs no DNS before the chain carrying
     *  DNS is up. */
    @Test
    fun `defaults to the warp anycast endpoint`() = runTest {
        val exit = DefaultWarpRegistrar(FakeClient(OK_RESPONSE)).register(0L).getOrNull()

        assertEquals("162.159.192.1", exit?.endpointHost)
        assertEquals(2408, exit?.endpointPort)
    }

    @Test
    fun `an unanswered registration fails instead of yielding half a key`() = runTest {
        val result = DefaultWarpRegistrar(FakeClient(response = null)).register(0L)

        assertTrue(result.isFailure)
    }

    @Test
    fun `a response in the wrong shape fails`() = runTest {
        val result = DefaultWarpRegistrar(FakeClient("""{"unexpected":true}""")).register(0L)

        assertTrue(result.isFailure)
    }
}
