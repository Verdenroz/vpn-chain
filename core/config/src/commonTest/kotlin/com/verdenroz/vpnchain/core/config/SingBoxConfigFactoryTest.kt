package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ProtonWireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun profile(entry: ProtonWireGuardEntry? = null) = ChainProfile(
    vpsIp = "89.127.235.38",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
    localProxyPort = 1080,
    protonEntry = entry,
)

private fun entry() = ProtonWireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "146.70.198.34",
)

private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonArray.taggedWith(tag: String): JsonObject? =
    map { it.jsonObject }.firstOrNull { it["tag"]?.jsonPrimitive?.content == tag }

class SingBoxConfigFactoryTest {

    @Test
    fun `proxy config listens on the profile's local port and never opens a tun`() {
        val config = parse(SingBoxConfigFactory.mixedProxyConfig(profile()))

        val inbound = config.array("inbounds").single().jsonObject
        assertEquals("mixed", inbound.getValue("type").jsonPrimitive.content)
        assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
        assertEquals(1080, inbound.getValue("listen_port").jsonPrimitive.int)
        assertFalse("\"tun\"" in SingBoxConfigFactory.mixedProxyConfig(profile()))
    }

    /** The relay-only path leaves interface detection off so it can't fight the
     *  real ProtonVPN app's routing. */
    @Test
    fun `proxy config keeps auto_detect_interface off and stays tcp-only`() {
        val config = parse(SingBoxConfigFactory.mixedProxyConfig(profile()))

        assertFalse(config.getValue("route").jsonObject.getValue("auto_detect_interface").jsonPrimitive.boolean)
        val vless = config.array("outbounds").taggedWith("proxy")
        assertEquals("tcp", vless?.getValue("network")?.jsonPrimitive?.content)
    }

    @Test
    fun `tun config carries udp so the whole system can be routed`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val vless = config.array("outbounds").taggedWith("vless-proxy")
        assertTrue(vless?.containsKey("network") == false, "TUN mode must not be tcp-only")
        assertEquals("xudp", vless?.getValue("packet_encoding")?.jsonPrimitive?.content)
    }

    /** 10.19.19.1/30 avoids the Docker bridge range sing-box otherwise collides with. */
    @Test
    fun `tun address stays off the common docker bridge range`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val tun = config.array("inbounds").single().jsonObject
        val address = tun.array("address").single().jsonPrimitive.content
        assertEquals("10.19.19.1/30", address)
        assertFalse(address.startsWith("172."))
    }

    @Test
    fun `mtu drops to fit inside the wireguard entry when one is configured`() {
        val withEntry = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))
        val relayOnly = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        assertEquals(1280, withEntry.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
        assertEquals(1400, relayOnly.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `the relay detours through the entry hop only when one is configured`() {
        val withEntry = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))
        val relayOnly = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        assertEquals(
            "proton-entry",
            withEntry.array("outbounds").taggedWith("vless-proxy")?.getValue("detour")?.jsonPrimitive?.content,
        )
        assertTrue(relayOnly.array("outbounds").taggedWith("vless-proxy")?.containsKey("detour") == false)
        assertTrue(relayOnly["endpoints"] == null, "relay-only chain must declare no wireguard endpoint")
    }

    @Test
    fun `the wireguard endpoint routes everything through the peer`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))

        val peer = config.array("endpoints").single().jsonObject.array("peers").single().jsonObject
        assertEquals("146.70.198.34", peer.getValue("address").jsonPrimitive.content)
        assertEquals(51820, peer.getValue("port").jsonPrimitive.int)
        assertEquals("0.0.0.0/0", peer.array("allowed_ips").single().jsonPrimitive.content)
    }

    /** QUIC blackholes under the nested small MTU, so it is rejected to force
     *  browsers back onto TCP rather than letting page loads stall. */
    @Test
    fun `tun config rejects udp 443`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))

        val rules = config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
        val quic = rules.firstOrNull { it["port"]?.jsonPrimitive?.int == 443 }
        assertEquals("udp", quic?.getValue("network")?.jsonPrimitive?.content)
        assertEquals("reject", quic?.getValue("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `dns is resolved through the relay, never on the local network`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))

        val server = config.getValue("dns").jsonObject.array("servers").single().jsonObject
        assertEquals("vless-proxy", server.getValue("detour").jsonPrimitive.content)
        assertEquals("ipv4_only", config.getValue("dns").jsonObject.getValue("strategy").jsonPrimitive.content)
    }

    @Test
    fun `reality credentials are carried on the vless outbound`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val tls = config.array("outbounds").taggedWith("vless-proxy")?.getValue("tls")?.jsonObject
        val reality = tls?.getValue("reality")?.jsonObject
        assertEquals("pubkey", reality?.getValue("public_key")?.jsonPrimitive?.content)
        assertEquals("shortid", reality?.getValue("short_id")?.jsonPrimitive?.content)
        assertTrue(tls?.getValue("utls")?.jsonObject?.getValue("enabled")?.jsonPrimitive?.boolean == true)
    }
}
