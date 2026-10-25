package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WireGuardConfigTest {

    @Test
    fun `renders a tun that routes everything to the peer`() {
        val config = render()

        assertEquals("tun", config.array("inbounds").single().obj().text("type"))
        assertEquals("entry-hop", config.getValue("route").obj().text("final"))
        assertEquals("wireguard", config.array("endpoints").single().obj().text("type"))
    }

    @Test
    fun `has no relay and no warp tail`() {
        val raw = SingBoxConfigFactory.wireGuardConfig(entry())

        assertFalse("vless" in raw)
        assertFalse("warp-exit" in raw)
        assertEquals(listOf("direct"), parse(raw).array("outbounds").map { it.obj().text("type") })
    }

    @Test
    fun `carries the peer settings from the config`() {
        val endpoint = render(entry(presharedKey = "psk", mtu = 1380, keepaliveSeconds = 15))
            .array("endpoints").single().obj()

        assertEquals(1380, endpoint.getValue("mtu").jsonPrimitive.int)
        val peer = endpoint.array("peers").single().obj()
        assertEquals("psk", peer.text("pre_shared_key"))
        assertEquals(15, peer.getValue("persistent_keepalive_interval").jsonPrimitive.int)
        assertEquals("198.51.100.7", peer.text("address"))
    }

    @Test
    fun `sizes the tun to the peer mtu`() {
        val tun = render(entry(mtu = 1380)).array("inbounds").single().obj()

        assertEquals(1380, tun.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `splits comma separated addresses and opens v6 only when one is given`() {
        val dual = render(entry(address = "10.2.0.2/32,fd00::2/128")).array("endpoints").single().obj()
        val v4 = render().array("endpoints").single().obj()

        assertEquals(listOf("10.2.0.2/32", "fd00::2/128"), dual.array("address").map { it.jsonPrimitive.content })
        assertEquals(listOf("0.0.0.0/0", "::/0"), dual.allowedIps())
        assertEquals(listOf("0.0.0.0/0"), v4.allowedIps())
    }

    @Test
    fun `resolves names through the peer dns when it has one`() {
        val dns = render(entry(dns = "10.2.0.1")).getValue("dns").obj()

        val server = dns.array("servers").single().obj()
        assertEquals("10.2.0.1", server.text("server"))
        assertEquals("entry-hop", server.text("detour"))
        assertEquals("dns-entry", dns.text("final"))
    }

    @Test
    fun `falls back to a public resolver dialled through the peer`() {
        val server = render().getValue("dns").obj().array("servers").single().obj()

        assertEquals("1.1.1.1", server.text("server"))
        assertEquals("entry-hop", server.text("detour"))
    }

    @Test
    fun `downloads blocklists through the peer`() {
        val config = Json.parseToJsonElement(
            SingBoxConfigFactory.wireGuardConfig(entry(), dnsFilter = DnsFilter.Malware),
        ).jsonObject

        val detours = config.getValue("route").obj().array("rule_set").map { it.obj().text("download_detour") }
        assertEquals(listOf("entry-hop"), detours)
    }

    @Test
    fun `leaves out the local network exclusions unless asked`() {
        val plain = render().array("inbounds").single().obj()
        val local = Json.parseToJsonElement(
            SingBoxConfigFactory.wireGuardConfig(entry(), allowLocalNetworks = true),
        ).jsonObject.array("inbounds").single().obj()

        assertNull(plain["route_exclude_address"])
        assertEquals(4, local.array("route_exclude_address").size)
    }

    private fun render(entry: WireGuardEntry = entry()): JsonObject =
        parse(SingBoxConfigFactory.wireGuardConfig(entry))

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun entry(
        address: String = "10.2.0.2/32",
        dns: String? = null,
        presharedKey: String? = null,
        mtu: Int? = null,
        keepaliveSeconds: Int? = null,
    ) = WireGuardEntry(
        privateKey = "priv",
        address = address,
        peerPublicKey = "peer",
        endpointHost = "198.51.100.7",
        dns = dns,
        presharedKey = presharedKey,
        mtu = mtu,
        keepaliveSeconds = keepaliveSeconds,
    )
}

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonElement.obj(): JsonObject = jsonObject

private fun JsonObject.allowedIps(): List<String> =
    array("peers").single().obj().array("allowed_ips").map { it.jsonPrimitive.content }
