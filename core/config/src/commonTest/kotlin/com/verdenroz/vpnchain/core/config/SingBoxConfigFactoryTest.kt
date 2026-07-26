package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DEFAULT_WARP_DOMAINS
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
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

private fun profile(entry: WireGuardEntry? = null) = ChainProfile(
    vpsIp = "89.127.235.38",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
    localProxyPort = 1080,
    entryHop = entry,
)

private fun entry(dns: String? = null) = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "146.70.198.34",
    dns = dns,
)

private fun warp() = WarpExit(
    privateKey = "warp-priv",
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "warp-peer",
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
     *  an external VPN app's routing. */
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
        val addresses = tun.array("address").map { it.jsonPrimitive.content }
        assertEquals("10.19.19.1/30", addresses.first())
        assertFalse(addresses.first().startsWith("172."))
    }

    /** Without an IPv6 tun address, auto_route only installs an IPv4 default
     *  route, so IPv6 traffic silently skips the tunnel instead of failing shut. */
    @Test
    fun `tun carries an ipv6 address so auto_route can't skip that family`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val addresses = config.array("inbounds").single().jsonObject.array("address")
            .map { it.jsonPrimitive.content }
        assertTrue(addresses.any { ":" in it }, "expected an IPv6 CIDR alongside the IPv4 one: $addresses")
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
            "entry-hop",
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
    fun `dns is resolved through the relay when no entry dns is configured`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(entry())))

        val dns = config.getValue("dns").jsonObject
        val server = dns.array("servers").single().jsonObject
        assertEquals("vless-proxy", server.getValue("detour").jsonPrimitive.content)
        assertEquals("dns-remote", dns.getValue("final").jsonPrimitive.content)
        assertEquals("ipv4_only", dns.getValue("strategy").jsonPrimitive.content)
    }

    /** Entry-side filtering only works if its DNS IP is reachable — that IP
     *  lives on the peer's internal network, so it must dial through it. */
    @Test
    fun `entry dns is resolved through the entry peer, not the relay`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(entry(dns = "10.2.0.1"))))

        val dns = config.getValue("dns").jsonObject
        val server = dns.array("servers").single().jsonObject
        assertEquals("10.2.0.1", server.getValue("server").jsonPrimitive.content)
        assertEquals("entry-hop", server.getValue("detour").jsonPrimitive.content)
        assertEquals("udp", server.getValue("type").jsonPrimitive.content)
        assertEquals("dns-entry", dns.getValue("final").jsonPrimitive.content)
    }

    @Test
    fun `dns falls back to the relay when there is no entry hop at all`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val server = config.getValue("dns").jsonObject.array("servers").single().jsonObject
        assertEquals("vless-proxy", server.getValue("detour").jsonPrimitive.content)
    }

    @Test
    fun `dns filtering rejects the blocklist and leaves resolution otherwise alone`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), dnsFilter = DnsFilter.AdsAndTrackers),
        )

        val dns = config.getValue("dns").jsonObject
        val rule = dns.array("rules").single().jsonObject
        assertEquals(
            listOf("blocklist-threats", "blocklist-ads", "blocklist-ads-trackers"),
            rule.array("rule_set").map { it.jsonPrimitive.content },
        )
        assertEquals("reject", rule.getValue("action").jsonPrimitive.content)
        // The upstream server is untouched; only matching names are refused.
        assertEquals("dns-remote", dns.getValue("final").jsonPrimitive.content)
    }

    /** Level 1: threats blocked, ads left to resolve. */
    @Test
    fun `malware-only filtering renders just the threat list`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), dnsFilter = DnsFilter.Malware),
        )

        val ruleSets = config.getValue("route").jsonObject.array("rule_set")
        assertEquals(
            listOf("blocklist-threats"),
            ruleSets.map { it.jsonObject.getValue("tag").jsonPrimitive.content },
        )
        val rule = config.getValue("dns").jsonObject.array("rules").single().jsonObject
        assertEquals(
            listOf("blocklist-threats"),
            rule.array("rule_set").map { it.jsonPrimitive.content },
        )
    }

    /** A blocklist fetched around the chain would announce the app to the very
     *  network the chain exists to hide it from. */
    @Test
    fun `the blocklist is downloaded through the relay`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), dnsFilter = DnsFilter.AdsAndTrackers),
        )

        val ruleSets = config.getValue("route").jsonObject.array("rule_set").map { it.jsonObject }
        assertEquals(3, ruleSets.size)
        for (ruleSet in ruleSets) {
            assertEquals("remote", ruleSet.getValue("type").jsonPrimitive.content)
            assertEquals("vless-proxy", ruleSet.getValue("download_detour").jsonPrimitive.content)
        }
    }

    @Test
    fun `no filtering renders no rule-set and no dns rules`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), dnsFilter = DnsFilter.Off))

        assertFalse("rules" in config.getValue("dns").jsonObject)
        assertFalse("rule_set" in config.getValue("route").jsonObject)
        assertFalse("experimental" in config)
    }

    /**
     * sing-box takes one `experimental` block. Rendering the cache separately
     * from the clash API would silently drop whichever came first — the stats
     * readout, or the rule-set cache that keeps the blocklist off the wire.
     */
    @Test
    fun `the rule-set cache and the clash api share one experimental block`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile = profile(),
                clashApi = ClashApi(port = 41739, secret = "s3cret"),
                dnsFilter = DnsFilter.AdsAndTrackers,
                cachePath = "/home/u/.config/vpn-chain/singbox-cache.db",
            ),
        )

        val experimental = config.getValue("experimental").jsonObject
        assertEquals(
            41739,
            experimental.getValue("clash_api").jsonObject.getValue("external_controller")
                .jsonPrimitive.content.substringAfter(':').toInt(),
        )
        val cache = experimental.getValue("cache_file").jsonObject
        assertTrue(cache.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(
            "/home/u/.config/vpn-chain/singbox-cache.db",
            cache.getValue("path").jsonPrimitive.content,
        )
    }

    @Test
    fun `filtering applies to a single-hop chain the same as one with an entry hop`() {
        val singleHop = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), dnsFilter = DnsFilter.AdsAndTrackers),
        )
        val withEntry = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry(dns = "10.2.0.1")),
                dnsFilter = DnsFilter.AdsAndTrackers,
            ),
        )

        assertEquals(
            singleHop.getValue("route").jsonObject.array("rule_set"),
            withEntry.getValue("route").jsonObject.array("rule_set"),
        )
        assertEquals(
            singleHop.getValue("dns").jsonObject.array("rules"),
            withEntry.getValue("dns").jsonObject.array("rules"),
        )
    }

    /**
     * The tail is what changes the address sites see. Dialling it through the
     * relay is what keeps it a tail rather than a second path around the chain
     * — and on desktop, a WARP handshake on the bare link is rejected outright
     * by the kill switch.
     */
    @Test
    fun `the warp tail is dialled through the relay and carries both families`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = warp(),
                warpMode = WarpMode.AllTraffic,
            ),
        )

        val tail = config.array("endpoints").taggedWith("warp-exit")
        assertEquals("vless-proxy", tail?.getValue("detour")?.jsonPrimitive?.content)
        assertEquals("warp-priv", tail?.getValue("private_key")?.jsonPrimitive?.content)
        val peer = tail?.array("peers")?.single()?.jsonObject
        assertEquals("162.159.192.1", peer?.getValue("address")?.jsonPrimitive?.content)
        assertEquals(2408, peer?.getValue("port")?.jsonPrimitive?.int)
        assertEquals(
            listOf("0.0.0.0/0", "::/0"),
            peer?.array("allowed_ips")?.map { it.jsonPrimitive.content },
        )
        // The entry hop is still there: the tail is an addition, not a swap.
        assertEquals(2, config.array("endpoints").size)
    }

    @Test
    fun `all-traffic mode sends everything out through the tail`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = warp(),
                warpMode = WarpMode.AllTraffic,
            ),
        )

        val route = config.getValue("route").jsonObject
        assertEquals("warp-exit", route.getValue("final").jsonPrimitive.content)
        // No domain rule: everything already goes there.
        assertTrue(route.array("rules").map { it.jsonObject }.none { "domain_suffix" in it })
    }

    @Test
    fun `blocked-sites mode routes only the listed names and leaves the exit alone`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = warp(),
                warpMode = WarpMode.BlockedSites,
                warpDomains = DEFAULT_WARP_DOMAINS,
            ),
        )

        val route = config.getValue("route").jsonObject
        assertEquals("vless-proxy", route.getValue("final").jsonPrimitive.content)
        val rule = route.array("rules").map { it.jsonObject }.single { "domain_suffix" in it }
        assertEquals("warp-exit", rule.getValue("outbound").jsonPrimitive.content)
        assertEquals("route", rule.getValue("action").jsonPrimitive.content)
        val suffixes = rule.array("domain_suffix").map { it.jsonPrimitive.content }
        assertTrue("reddit.com" in suffixes, suffixes.toString())
        assertTrue("chatgpt.com" in suffixes, suffixes.toString())
    }

    /**
     * The stored list is the whole rule — nothing is merged in underneath it,
     * or removing an entry in settings wouldn't actually stop routing it.
     */
    @Test
    fun `only the given domains are routed, with no built-in list behind them`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.BlockedSites,
                warpDomains = listOf("example.com"),
            ),
        )

        val rule = config.getValue("route").jsonObject.array("rules")
            .map { it.jsonObject }.single { "domain_suffix" in it }
        assertEquals(
            listOf("example.com"),
            rule.array("domain_suffix").map { it.jsonPrimitive.content },
        )
    }

    /** Typed by hand, so it arrives however a URL bar shows it. */
    @Test
    fun `domains are normalised down to the suffix sing-box matches`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.BlockedSites,
                warpDomains = listOf(
                    "https://Example.com/path",
                    "*.news.site",
                    " bad ",
                    "reddit.com",
                    "REDDIT.com",
                ),
            ),
        )

        val rule = config.getValue("route").jsonObject.array("rules")
            .map { it.jsonObject }.single { "domain_suffix" in it }
        val suffixes = rule.array("domain_suffix").map { it.jsonPrimitive.content }
        assertTrue("example.com" in suffixes, suffixes.toString())
        assertTrue("news.site" in suffixes, suffixes.toString())
        // No dot, so it was never a domain; and case aliases collapse to one.
        assertFalse("bad" in suffixes)
        assertEquals(1, suffixes.count { it == "reddit.com" })
    }

    /** Selective mode with nothing listed routes nothing, so there is no tail
     *  to declare — an endpoint no rule ever reaches is just dead config. */
    @Test
    fun `blocked-sites mode with an empty list renders no tail at all`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = warp(),
                warpMode = WarpMode.BlockedSites,
                warpDomains = emptyList(),
            ),
        )

        assertTrue(config.array("endpoints").taggedWith("warp-exit") == null)
        val route = config.getValue("route").jsonObject
        assertEquals("vless-proxy", route.getValue("final").jsonPrimitive.content)
        assertTrue(route.array("rules").map { it.jsonObject }.none { "domain_suffix" in it })
    }

    @Test
    fun `mode off renders no tail even with credentials in hand`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = warp(),
                warpMode = WarpMode.Off,
            ),
        )

        assertTrue(config.array("endpoints").taggedWith("warp-exit") == null)
        assertEquals("vless-proxy", config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
    }

    /**
     * Registration can fail on a network that blocks Cloudflare, and the chain
     * still has to come up — one hop shorter, not not at all.
     */
    @Test
    fun `a missing registration degrades to the relay exit instead of breaking`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(entry()),
                warp = null,
                warpMode = WarpMode.AllTraffic,
            ),
        )

        assertEquals("vless-proxy", config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        assertTrue(config.array("endpoints").taggedWith("warp-exit") == null)
    }

    /** The tail is another 1280 WireGuard hop, so it caps the TUN the same way
     *  an entry hop does — even when there is no entry hop in front. */
    @Test
    fun `the tail caps the tun mtu on a single-hop chain`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.AllTraffic,
            ),
        )

        assertEquals(1280, config.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `the proxy config dials the tail through its own relay outbound`() {
        val config = parse(
            SingBoxConfigFactory.mixedProxyConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.AllTraffic,
            ),
        )

        val tail = config.array("endpoints").taggedWith("warp-exit")
        assertEquals("proxy", tail?.getValue("detour")?.jsonPrimitive?.content)
        assertEquals("warp-exit", config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
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
