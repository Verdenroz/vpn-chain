package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RELAY_ONLY = """
    # the CLI's own file, comments and all
    VPS_IP=89.127.235.38
    VLESS_UUID=3f7b1c2e-0000-4444-8888-aabbccddeeff
    REALITY_PUBKEY=Zx9-publickey
    SHORT_ID=a1b2c3d4
""".trimIndent()

class SecretsEnvParserTest {

    @Test
    fun `parses the four required keys and defaults the rest`() {
        val profile = SecretsEnvParser.parse(RELAY_ONLY).getOrThrow()

        assertEquals("89.127.235.38", profile.vpsIp)
        assertEquals("3f7b1c2e-0000-4444-8888-aabbccddeeff", profile.vlessUuid)
        assertEquals("Zx9-publickey", profile.realityPublicKey)
        assertEquals("a1b2c3d4", profile.shortId)
        assertEquals(ChainProfile.DEFAULT_SNI, profile.sni)
        assertEquals(ChainProfile.DEFAULT_SERVER_PORT, profile.serverPort)
        assertNull(profile.entryHop)
    }

    @Test
    fun `ignores comments, blank lines and surrounding quotes`() {
        val profile = SecretsEnvParser.parse(
            """
            # leading comment

            VPS_IP="89.127.235.38"
            VLESS_UUID='not-stripped'
            REALITY_PUBKEY=key
              SHORT_ID=short
            # trailing comment
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("89.127.235.38", profile.vpsIp)
        assertEquals("short", profile.shortId)
    }

    @Test
    fun `names every missing key rather than failing on the first`() {
        val result = SecretsEnvParser.parse("VPS_IP=1.2.3.4")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("VLESS_UUID" in message, message)
        assertTrue("REALITY_PUBKEY" in message, message)
        assertTrue("SHORT_ID" in message, message)
    }

    @Test
    fun `treats a blank required value as missing`() {
        val result = SecretsEnvParser.parse("$RELAY_ONLY\nSHORT_ID=")

        assertTrue(result.isFailure)
    }

    @Test
    fun `builds the entry hop only when every wireguard key is present`() {
        val complete = SecretsEnvParser.parse(
            """
            $RELAY_ONLY
            ENTRY_PRIVKEY=priv
            ENTRY_ADDRESS=10.2.0.2/32
            ENTRY_PEER_PUBKEY=peer
            ENTRY_HOST=146.70.198.34
            """.trimIndent(),
        ).getOrThrow()

        val entry = assertNotNull(complete.entryHop)
        assertEquals("146.70.198.34", entry.endpointHost)
        assertEquals(WireGuardEntry.DEFAULT_ENDPOINT_PORT, entry.endpointPort)
    }

    @Test
    fun `carries the entry dns line when present`() {
        val withDns = SecretsEnvParser.parse(
            """
            $RELAY_ONLY
            ENTRY_PRIVKEY=priv
            ENTRY_ADDRESS=10.2.0.2/32
            ENTRY_PEER_PUBKEY=peer
            ENTRY_HOST=146.70.198.34
            ENTRY_DNS=10.2.0.1
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("10.2.0.1", assertNotNull(withDns.entryHop).dns)
    }

    @Test
    fun `dns stays null when the wireguard config had no dns line`() {
        val complete = SecretsEnvParser.parse(
            """
            $RELAY_ONLY
            ENTRY_PRIVKEY=priv
            ENTRY_ADDRESS=10.2.0.2/32
            ENTRY_PEER_PUBKEY=peer
            ENTRY_HOST=146.70.198.34
            """.trimIndent(),
        ).getOrThrow()

        assertNull(assertNotNull(complete.entryHop).dns)
    }

    /** A half-filled entry hop must not silently become a partial tunnel. */
    @Test
    fun `drops the entry hop when a wireguard key is missing`() {
        val partial = SecretsEnvParser.parse(
            """
            $RELAY_ONLY
            ENTRY_PRIVKEY=priv
            ENTRY_ADDRESS=10.2.0.2/32
            """.trimIndent(),
        ).getOrThrow()

        assertNull(partial.entryHop)
    }

    @Test
    fun `falls back to defaults when ports are not numbers`() {
        val profile = SecretsEnvParser.parse("$RELAY_ONLY\nSERVER_PORT=https").getOrThrow()

        assertEquals(ChainProfile.DEFAULT_SERVER_PORT, profile.serverPort)
    }

    /** format() feeds the QR pairing payload, so an entry DNS value must
     *  survive the round trip onto the paired device too. */
    @Test
    fun `format round-trips the entry dns line through parse`() {
        val original = SecretsEnvParser.parse(
            """
            $RELAY_ONLY
            ENTRY_PRIVKEY=priv
            ENTRY_ADDRESS=10.2.0.2/32
            ENTRY_PEER_PUBKEY=peer
            ENTRY_HOST=146.70.198.34
            ENTRY_DNS=10.2.0.1
            """.trimIndent(),
        ).getOrThrow()

        val roundTripped = SecretsEnvParser.parse(SecretsEnvParser.format(original)).getOrThrow()

        assertEquals("10.2.0.1", roundTripped.entryHop?.dns)
    }

    @Test
    fun `keeps explicit overrides for optional values`() {
        val profile = SecretsEnvParser.parse(
            "$RELAY_ONLY\nSNI=example.org\nSERVER_PORT=8443\nLOCAL_PROXY_PORT=1081",
        ).getOrThrow()

        assertEquals("example.org", profile.sni)
        assertEquals(8443, profile.serverPort)
        assertEquals(1081, profile.localProxyPort)
    }
}
