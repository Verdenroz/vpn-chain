package com.verdenroz.vpnchain.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireGuardConfParserTest {

    @Test
    fun `parses a provider style config`() {
        val entry = WireGuardConfParser.parse(PROTON).getOrThrow()

        assertEquals(PRIVATE, entry.privateKey)
        assertEquals("10.2.0.2/32", entry.address)
        assertEquals("10.2.0.1", entry.dns)
        assertEquals(PUBLIC, entry.peerPublicKey)
        assertEquals("203.0.113.9", entry.endpointHost)
        assertEquals(51820, entry.endpointPort)
        assertNull(entry.presharedKey)
        assertNull(entry.mtu)
        assertNull(entry.keepaliveSeconds)
    }

    @Test
    fun `keys are case insensitive and comments are ignored`() {
        val entry = WireGuardConfParser.parse(
            """
            # generated for a laptop
            [interface]
            privatekey = $PRIVATE # secret
            ADDRESS=10.2.0.2/32
            [PEER]
            publickey=$PUBLIC
            allowedips=0.0.0.0/0
            endpoint=203.0.113.9:51820
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(PRIVATE, entry.privateKey)
        assertEquals(PUBLIC, entry.peerPublicKey)
    }

    @Test
    fun `reads comma separated v4 and v6 addresses`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("Address = 10.2.0.2/32", "Address = 10.2.0.2/32, fd00::2/128"),
        ).getOrThrow()

        assertEquals("10.2.0.2/32,fd00::2/128", entry.address)
    }

    @Test
    fun `adds a prefix to a bare address`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("Address = 10.2.0.2/32", "Address = 10.2.0.2, fd00::2"),
        ).getOrThrow()

        assertEquals("10.2.0.2/32,fd00::2/128", entry.address)
    }

    @Test
    fun `reads a bracketed v6 endpoint`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("203.0.113.9:51820", "[2001:db8::1]:51821"),
        ).getOrThrow()

        assertEquals("2001:db8::1", entry.endpointHost)
        assertEquals(51821, entry.endpointPort)
    }

    @Test
    fun `refuses a hostname endpoint`() {
        val error = failure(PROTON.replace("203.0.113.9:51820", "node-is-01.protonvpn.net:51820"))

        assertIs<WireGuardConfError.HostnameEndpoint>(error)
        assertEquals("Use an IP endpoint.", error.message)
    }

    @Test
    fun `reads mtu preshared key and keepalive`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("DNS = 10.2.0.1", "DNS = 10.2.0.1\nMTU = 1380")
                .replace("AllowedIPs", "PresharedKey = $PRESHARED\nPersistentKeepalive = 25\nAllowedIPs"),
        ).getOrThrow()

        assertEquals(1380, entry.mtu)
        assertEquals(PRESHARED, entry.presharedKey)
        assertEquals(25, entry.keepaliveSeconds)
    }

    @Test
    fun `a zero keepalive means off`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("AllowedIPs", "PersistentKeepalive = 0\nAllowedIPs"),
        ).getOrThrow()

        assertNull(entry.keepaliveSeconds)
    }

    @Test
    fun `keeps the first dns server and drops search domains`() {
        val entry = WireGuardConfParser.parse(
            PROTON.replace("DNS = 10.2.0.1", "DNS = 10.2.0.1, 10.2.0.3, corp.example"),
        ).getOrThrow()

        assertEquals("10.2.0.1", entry.dns)
    }

    @Test
    fun `dns is optional`() {
        val entry = WireGuardConfParser.parse(PROTON.replace("DNS = 10.2.0.1\n", "")).getOrThrow()

        assertNull(entry.dns)
    }

    @Test
    fun `accepts a dual stack full tunnel`() {
        val entry = WireGuardConfParser.parse(PROTON.replace("0.0.0.0/0", "0.0.0.0/0, ::/0"))

        assertTrue(entry.isSuccess)
    }

    @Test
    fun `accepts a full tunnel written as two halves`() {
        val entry = WireGuardConfParser.parse(PROTON.replace("0.0.0.0/0", "0.0.0.0/1, 128.0.0.0/1"))

        assertTrue(entry.isSuccess)
    }

    @Test
    fun `refuses one half of a split default route`() {
        val error = failure(PROTON.replace("0.0.0.0/0", "0.0.0.0/1"))

        assertIs<WireGuardConfError.SplitTunnel>(error)
    }

    @Test
    fun `refuses a split tunnel`() {
        val error = failure(PROTON.replace("0.0.0.0/0", "10.0.0.0/8, 192.168.1.0/24"))

        assertIs<WireGuardConfError.SplitTunnel>(error)
    }

    @Test
    fun `refuses two peers`() {
        val error = failure(PROTON + PEER_BLOCK)

        assertIs<WireGuardConfError.PeerCount>(error)
        assertEquals("Config has 2 peers. Use one.", error.message)
    }

    @Test
    fun `refuses a config with no peer`() {
        val error = failure(PROTON.substringBefore("[Peer]"))

        assertEquals("Config has no peer.", error.message)
    }

    @Test
    fun `names each missing field`() {
        assertEquals("Missing PrivateKey.", failure(PROTON.replace("PrivateKey = $PRIVATE\n", "")).message)
        assertEquals("Missing Address.", failure(PROTON.replace("Address = 10.2.0.2/32\n", "")).message)
        assertEquals("Missing PublicKey.", failure(PROTON.replace("PublicKey = $PUBLIC\n", "")).message)
        assertEquals("Missing AllowedIPs.", failure(PROTON.replace("AllowedIPs = 0.0.0.0/0\n", "")).message)
        assertEquals("Missing Endpoint.", failure(PROTON.replace("Endpoint = 203.0.113.9:51820", "")).message)
    }

    @Test
    fun `refuses a malformed key`() {
        val error = failure(PROTON.replace(PRIVATE, "not-a-key"))

        assertEquals("Bad PrivateKey.", error.message)
    }

    @Test
    fun `refuses an endpoint without a usable port`() {
        assertEquals("Bad Endpoint.", failure(PROTON.replace("203.0.113.9:51820", "203.0.113.9")).message)
        assertEquals("Bad Endpoint.", failure(PROTON.replace("203.0.113.9:51820", "203.0.113.9:0")).message)
        assertEquals("Bad Endpoint.", failure(PROTON.replace("203.0.113.9:51820", "203.0.113.9:70000")).message)
        assertEquals("Bad Endpoint.", failure(PROTON.replace("203.0.113.9:51820", "[2001:db8::1]")).message)
    }

    @Test
    fun `refuses endpoints that only look like addresses`() {
        listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "[1:2:3]", "[::1::2]", "[12345::1]", "[g::1]").forEach { host ->
            val error = failure(PROTON.replace("203.0.113.9:51820", "$host:51820"))

            assertEquals("Use an IP endpoint.", error.message, host)
        }
    }

    @Test
    fun `accepts compressed and full v6 endpoints`() {
        listOf("::1", "2001:db8::1", "2001:db8:0:0:0:0:0:1", "::ffff:192.0.2.1").forEach { host ->
            val entry = WireGuardConfParser.parse(PROTON.replace("203.0.113.9:51820", "[$host]:51820"))

            assertTrue(entry.isSuccess, host)
        }
    }

    @Test
    fun `refuses text that is not a config`() {
        assertEquals("Config has no peer.", failure("hello").message)
    }

    @Test
    fun `secrets stay out of toString`() {
        val entry = WireGuardConfParser.parse(PROTON.replace("AllowedIPs", "PresharedKey = $PRESHARED\nAllowedIPs"))
            .getOrThrow()

        val printed = entry.toString()

        assertFalse(PRIVATE in printed)
        assertFalse(PRESHARED in printed)
    }

    @Test
    fun `recognises a config by its interface section`() {
        assertTrue(WireGuardConfParser.looksLikeConf(PROTON))
        assertTrue(WireGuardConfParser.looksLikeConf("  [interface]\nPrivateKey = x"))
        assertFalse(WireGuardConfParser.looksLikeConf("VPS_IP=203.0.113.9"))
    }

    private fun failure(text: String): WireGuardConfError =
        assertIs<WireGuardConfError>(WireGuardConfParser.parse(text).exceptionOrNull())
}

private const val PRIVATE = "PRIVATEKEYPLACEHOLDERPRIVATEKEYPLACEHOLDER1="
private const val PUBLIC = "cHVibGljIGtleSBwbGFjZWhvbGRlciBmb3IgdGVzdHM="
private const val PRESHARED = "cHJlc2hhcmVkIGtleSBwbGFjZWhvbGRlciB0ZXN0cz0="

private val PROTON = """
    [Interface]
    # Key for laptop
    PrivateKey = $PRIVATE
    Address = 10.2.0.2/32
    DNS = 10.2.0.1

    [Peer]
    # CH#1
    PublicKey = $PUBLIC
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent() + "\n"

private val PEER_BLOCK = """

    [Peer]
    PublicKey = $PUBLIC
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.10:51820
""".trimIndent() + "\n"
