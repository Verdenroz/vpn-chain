package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun chain(
    exit: WireGuardEntry? = deviceExit(),
    entry: WireGuardEntry? = null,
    warpMode: WarpMode = WarpMode.Off,
    learnedWarpPath: String? = null,
) = parse(
    SingBoxConfigFactory.androidChainConfig(
        profile = profile(),
        entry = entry,
        warp = warp(),
        warpMode = warpMode,
        deviceExit = exit,
        probe = probe(),
        learnedWarpPath = learnedWarpPath,
    ),
)

private fun proxy(warpMode: WarpMode = WarpMode.Off, exit: WireGuardEntry? = deviceExit()) = parse(
    SingBoxConfigFactory.mixedProxyConfig(profile(), warp = warp(), warpMode = warpMode, deviceExit = exit, probe = probe()),
)

private fun JsonObject.route() = getValue("route").jsonObject

private fun JsonObject.finalOutbound() = route().getValue("final").jsonPrimitive.content

private fun JsonObject.probeRelayTarget() = route().array("rules").map { it.jsonObject }
    .first { it["inbound"]?.jsonPrimitive?.content == ProbeProxy.RELAY_TAG }
    .getValue("outbound").jsonPrimitive.content

class DeviceExitConfigTest {

    @Test
    fun `the exit is a wireguard endpoint dialled through the relay`() {
        val exit = chain().array("endpoints").taggedWith("device-exit")

        assertNotNull(exit)
        assertEquals("wireguard", exit.getValue("type").jsonPrimitive.content)
        assertEquals("vless-proxy", exit.getValue("detour").jsonPrimitive.content)
        assertEquals("198.51.100.30", exit.array("peers").single().jsonObject.getValue("address").jsonPrimitive.content)
    }

    @Test
    fun `endpoints list the entry hop, then the tail, then the exit`() {
        val tags = chain(entry = entry(), warpMode = WarpMode.Automatic).array("endpoints")
            .map { it.jsonObject.getValue("tag").jsonPrimitive.content }

        assertEquals(listOf("entry-hop", "warp-exit", "device-exit"), tags)
    }

    @Test
    fun `the exit takes only ipv4 unless its address has an ipv6 part`() {
        fun allowed(exit: WireGuardEntry) = chain(exit = exit).array("endpoints")
            .taggedWith("device-exit")!!.array("peers").single().jsonObject.array("allowed_ips").map { it.jsonPrimitive.content }

        assertEquals(listOf("0.0.0.0/0"), allowed(deviceExit()))
        assertEquals(listOf("0.0.0.0/0", "::/0"), allowed(deviceExit().copy(address = "10.5.0.2/32,fd00::2/128")))
    }

    @Test
    fun `the exit carries its preshared key and keepalive`() {
        val peer = chain(exit = deviceExit().copy(presharedKey = "psk", keepaliveSeconds = 15)).array("endpoints")
            .taggedWith("device-exit")!!.array("peers").single().jsonObject

        assertEquals("psk", peer.getValue("pre_shared_key").jsonPrimitive.content)
        assertEquals(15, peer.getValue("persistent_keepalive_interval").jsonPrimitive.int)
        assertEquals(
            25,
            chain().array("endpoints").taggedWith("device-exit")!!.array("peers").single().jsonObject
                .getValue("persistent_keepalive_interval").jsonPrimitive.int,
        )
        assertFalse(chain().array("endpoints").taggedWith("device-exit")!!.array("peers").single().jsonObject.containsKey("pre_shared_key"))
    }

    @Test
    fun `the exit mtu is capped at 1280 and caps the tun`() {
        fun mtus(exit: WireGuardEntry): Pair<Int, Int> {
            val config = chain(exit = exit)
            return config.array("endpoints").taggedWith("device-exit")!!.getValue("mtu").jsonPrimitive.int to
                config.array("inbounds").first().jsonObject.getValue("mtu").jsonPrimitive.int
        }

        assertEquals(1280 to 1280, mtus(deviceExit()))
        assertEquals(1280 to 1280, mtus(deviceExit().copy(mtu = 1500)))
        assertEquals(1200 to 1200, mtus(deviceExit().copy(mtu = 1200)))
    }

    @Test
    fun `no exit leaves the tun at its relay-only size`() {
        assertEquals(1400, chain(exit = null).array("inbounds").first().jsonObject.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `the exit becomes the final route`() {
        assertEquals("device-exit", chain().finalOutbound())
        assertEquals("vless-proxy", chain(exit = null).finalOutbound())
    }

    @Test
    fun `warp all traffic keeps the final route`() {
        assertEquals("warp-exit", chain(warpMode = WarpMode.AllTraffic).finalOutbound())
    }

    @Test
    fun `learned warp sites still go to warp`() {
        val rules = chain(warpMode = WarpMode.Automatic, learnedWarpPath = "/tmp/warp.json").route().array("rules")
            .map { it.jsonObject }

        val learned = rules.first { it["rule_set"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content == "learned-warp" }
        assertEquals("warp-exit", learned.getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `the relay probe leaves by the exit`() {
        assertEquals("device-exit", chain().probeRelayTarget())
        assertEquals("vless-proxy", chain(exit = null).probeRelayTarget())
    }

    @Test
    fun `the exit's resolver is dialled through the exit`() {
        val dns = chain(exit = deviceExit(dns = "10.5.0.1")).getValue("dns").jsonObject

        val server = dns.array("servers").taggedWith("dns-exit")!!
        assertEquals("device-exit", server.getValue("detour").jsonPrimitive.content)
        assertEquals("dns-exit", dns.getValue("final").jsonPrimitive.content)
    }

    @Test
    fun `an exit without a resolver sends queries through the exit, not to the entry resolver`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), entry(dns = "10.2.0.1"), deviceExit = deviceExit(), dnsFilter = DnsFilter.Off),
        )

        val dns = config.getValue("dns").jsonObject
        assertEquals("dns-remote", dns.getValue("final").jsonPrimitive.content)
        assertEquals("device-exit", dns.array("servers").taggedWith("dns-remote")!!.getValue("detour").jsonPrimitive.content)
        assertNull(dns.array("servers").taggedWith("dns-entry"))
    }

    @Test
    fun `without an exit the entry resolver stays`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry(dns = "10.2.0.1"), dnsFilter = DnsFilter.Off))

        assertEquals("dns-entry", config.getValue("dns").jsonObject.getValue("final").jsonPrimitive.content)
    }

    @Test
    fun `the public resolver detours through the exit`() {
        val server = chain().getValue("dns").jsonObject.array("servers").taggedWith("dns-remote")!!

        assertEquals("device-exit", server.getValue("detour").jsonPrimitive.content)
    }

    @Test
    fun `no exit renders no exit endpoint`() {
        assertNull(chain(exit = null)["endpoints"]?.jsonArray?.taggedWith("device-exit"))
    }

    @Test
    fun `the proxy config dials the exit through the relay and ends there`() {
        val config = proxy()

        val exit = config.array("endpoints").taggedWith("device-exit")!!
        assertEquals("proxy", exit.getValue("detour").jsonPrimitive.content)
        assertEquals("device-exit", config.finalOutbound())
        assertEquals("device-exit", config.probeRelayTarget())
    }

    @Test
    fun `the proxy config renders a dns block for the exit only`() {
        val dns = proxy().getValue("dns").jsonObject

        assertEquals("device-exit", dns.array("servers").taggedWith("dns-remote")!!.getValue("detour").jsonPrimitive.content)
        assertFalse(proxy(exit = null).containsKey("dns"))
    }

    @Test
    fun `the proxy config keeps warp as the final route in all traffic`() {
        assertEquals("warp-exit", proxy(warpMode = WarpMode.AllTraffic).finalOutbound())
    }

    @Test
    fun `a wireguard only config never carries an exit`() {
        assertFalse("device-exit" in SingBoxConfigFactory.wireGuardConfig(entry()))
    }
}
