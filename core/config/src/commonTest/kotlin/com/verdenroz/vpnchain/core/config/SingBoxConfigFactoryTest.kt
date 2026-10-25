package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
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

private fun profile() = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
    localProxyPort = 1080,
)

private fun entry(dns: String? = null) = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.20",
    dns = dns,
)

private fun warp() = WarpExit(
    privateKey = "warp-priv",
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "warp-peer",
)

private fun probe() = ProbeProxy(relayPort = 41001, warpPort = 41002, fallbackPort = 41003, username = "u", password = "p")

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

    @Test
    fun `vless dials give up after 3s on both the proxy and tun paths`() {
        val proxy = parse(SingBoxConfigFactory.mixedProxyConfig(profile())).array("outbounds").taggedWith("proxy")
        val tun = parse(SingBoxConfigFactory.androidChainConfig(profile())).array("outbounds").taggedWith("vless-proxy")

        assertEquals("3s", proxy?.getValue("connect_timeout")?.jsonPrimitive?.content)
        assertEquals("3s", tun?.getValue("connect_timeout")?.jsonPrimitive?.content)
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
    fun `local ranges are excluded from the tun route when allowed`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), allowLocalNetworks = true))

        val excluded = config.array("inbounds").single().jsonObject
            .array("route_exclude_address").map { it.jsonPrimitive.content }
        assertEquals(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"), excluded)
    }

    @Test
    fun `tun route excludes nothing by default`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        val tun = config.array("inbounds").single().jsonObject
        assertFalse(tun.containsKey("route_exclude_address"))
    }

    @Test
    fun `log gains an output path when one is given`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), logPath = "/run/vpn-chain/relay.log"))

        val log = config.getValue("log").jsonObject
        assertEquals("/run/vpn-chain/relay.log", log.getValue("output").jsonPrimitive.content)
    }

    @Test
    fun `log writes to stdout by default`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        assertFalse(config.getValue("log").jsonObject.containsKey("output"))
    }

    @Test
    fun `mtu drops to fit inside the wireguard entry when one is configured`() {
        val withEntry = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry()))
        val relayOnly = parse(SingBoxConfigFactory.androidChainConfig(profile()))

        assertEquals(1280, withEntry.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
        assertEquals(1400, relayOnly.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `a lower entry mtu caps the tun`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry().copy(mtu = 1200)))

        assertEquals(1200, config.array("inbounds").single().jsonObject.getValue("mtu").jsonPrimitive.int)
    }

    @Test
    fun `the relay detours through the entry hop only when one is configured`() {
        val withEntry = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry()))
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
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry()))

        val peer = config.array("endpoints").single().jsonObject.array("peers").single().jsonObject
        assertEquals("198.51.100.20", peer.getValue("address").jsonPrimitive.content)
        assertEquals(51820, peer.getValue("port").jsonPrimitive.int)
        assertEquals("0.0.0.0/0", peer.array("allowed_ips").single().jsonPrimitive.content)
    }

    /** QUIC blackholes under the nested small MTU, so it is rejected to force
     *  browsers back onto TCP rather than letting page loads stall. */
    @Test
    fun `tun config rejects udp 443`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry()))

        val rules = config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
        val quic = rules.firstOrNull { it["port"]?.jsonPrimitive?.int == 443 }
        assertEquals("udp", quic?.getValue("network")?.jsonPrimitive?.content)
        assertEquals("reject", quic?.getValue("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `dns is resolved through the relay when no entry dns is configured`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry()))

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
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), entry(dns = "10.2.0.1")))

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
                profile(), entry(dns = "10.2.0.1"),
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
                profile(), entry(),
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
                profile(), entry(),
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
    fun `automatic mode declares the tail but leaves the exit alone`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
            ),
        )

        assertEquals("vless-proxy", config.array("endpoints").taggedWith("warp-exit")?.getValue("detour")?.jsonPrimitive?.content)
        val route = config.getValue("route").jsonObject
        assertEquals("vless-proxy", route.getValue("final").jsonPrimitive.content)
        assertTrue(route.array("rules").map { it.jsonObject }.none { "domain_suffix" in it })
    }

    @Test
    fun `mode off renders no tail even with credentials in hand`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry(),
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
                profile(), entry(),
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
    fun `the proxy config resolves names through the relay when traffic can leave elsewhere`() {
        val config = parse(
            SingBoxConfigFactory.mixedProxyConfig(profile(), warp = warp(), warpMode = WarpMode.Automatic, failOpen = true),
        )

        val server = config.getValue("dns").jsonObject.array("servers").single().jsonObject
        assertEquals("proxy", server.getValue("detour").jsonPrimitive.content)
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

    @Test
    fun `probe inbounds listen on loopback with credentials`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                probe = probe(),
            ),
        )

        val inbounds = config.array("inbounds")
        for ((tag, port) in listOf("probe-relay" to 41001, "probe-warp" to 41002)) {
            val inbound = inbounds.taggedWith(tag)
            assertEquals("mixed", inbound?.getValue("type")?.jsonPrimitive?.content)
            assertEquals("127.0.0.1", inbound?.getValue("listen")?.jsonPrimitive?.content)
            assertEquals(port, inbound?.getValue("listen_port")?.jsonPrimitive?.int)
            val user = inbound?.getValue("users")?.jsonArray?.single()?.jsonObject
            assertEquals("u", user?.getValue("username")?.jsonPrimitive?.content)
            assertEquals("p", user?.getValue("password")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `probe rules precede the learned rules and pick their exit by inbound`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                probe = probe(),
                learnedWarpPath = "/run/learned-warp.json",
            ),
        )

        val rules = config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
        val relayAt = rules.indexOfFirst { it["inbound"]?.jsonPrimitive?.content == "probe-relay" }
        val warpAt = rules.indexOfFirst { it["inbound"]?.jsonPrimitive?.content == "probe-warp" }
        val learnedAt = rules.indexOfFirst { "rule_set" in it }
        assertTrue(relayAt in 0 until learnedAt && warpAt in 0 until learnedAt)
        assertEquals("vless-proxy", rules[relayAt].getValue("outbound").jsonPrimitive.content)
        assertEquals("warp-exit", rules[warpAt].getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `no tail means no warp probe inbound or rule`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(profile(), warpMode = WarpMode.Off, probe = probe()),
        )

        assertEquals(null, config.array("inbounds").taggedWith("probe-warp"))
        assertFalse("probe-warp" in config.toString())
        assertTrue(config["endpoints"] == null)
    }

    @Test
    fun `no probe renders neither inbound`() {
        val config = parse(SingBoxConfigFactory.androidChainConfig(profile(), warp = warp(), warpMode = WarpMode.AllTraffic))

        assertEquals(1, config.array("inbounds").size)
        assertFalse("probe-" in config.toString())
    }

    @Test
    fun `the proxy config renders probes that exit through its own relay outbound`() {
        val config = parse(
            SingBoxConfigFactory.mixedProxyConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                probe = probe(),
            ),
        )

        val rules = config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
        assertEquals("proxy", rules.single { it["inbound"]?.jsonPrimitive?.content == "probe-relay" }
            .getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `probes are recovered from the rendered config`() {
        val raw = SingBoxConfigFactory.androidChainConfig(
            profile(),
            warp = warp(),
            warpMode = WarpMode.Automatic,
            probe = probe(),
        )

        assertEquals(probe().copy(fallbackPort = 0), ProbeProxy.fromConfig(raw))
        assertEquals(null, ProbeProxy.fromConfig(SingBoxConfigFactory.androidChainConfig(profile())))
    }

    @Test
    fun `learned sites are a local rule-set routed to the tail`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                learnedWarpPath = "/run/learned-warp.json",
            ),
        )

        val route = config.getValue("route").jsonObject
        val set = route.array("rule_set").taggedWith("learned-warp")
        assertEquals("local", set?.getValue("type")?.jsonPrimitive?.content)
        assertEquals("source", set?.getValue("format")?.jsonPrimitive?.content)
        assertEquals("/run/learned-warp.json", set?.getValue("path")?.jsonPrimitive?.content)
        val rules = route.array("rules").map { it.jsonObject }
        val learned = rules.single { "rule_set" in it }
        assertEquals("warp-exit", learned.getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `learned sites share the rule-set list with the blocklists`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                dnsFilter = DnsFilter.Malware,
                warp = warp(),
                warpMode = WarpMode.Automatic,
                learnedWarpPath = "/run/learned-warp.json",
            ),
        )

        val tags = config.getValue("route").jsonObject.array("rule_set").map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertEquals(listOf("blocklist-threats", "learned-warp"), tags)
    }

    @Test
    fun `learned sites are ignored outside automatic mode`() {
        val raw = SingBoxConfigFactory.androidChainConfig(
            profile(),
            warp = warp(),
            warpMode = WarpMode.AllTraffic,
            learnedWarpPath = "/run/learned-warp.json",
        )

        assertFalse("learned-warp" in raw)
    }

    @Test
    fun `the rule-set file lists the domains as suffixes`() {
        val parsed = parse(LearnedRuleSet.render(listOf("a.com", "b.org")))

        assertEquals(1, parsed.getValue("version").jsonPrimitive.int)
        val suffixes = parsed.array("rules").single().jsonObject.array("domain_suffix").map { it.jsonPrimitive.content }
        assertEquals(listOf("a.com", "b.org"), suffixes)
    }

    @Test
    fun `the clash api is recovered from the rendered config`() {
        val raw = SingBoxConfigFactory.androidChainConfig(profile(), clashApi = ClashApi(port = 9191, secret = "s3"))

        assertEquals(ClashApi(port = 9191, secret = "s3"), ClashApi.fromConfig(raw))
        assertEquals(null, ClashApi.fromConfig(SingBoxConfigFactory.androidChainConfig(profile())))
    }

    @Test
    fun `the fallback probe leaves by the entry hop when there is one`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                probe = probe(),
                failOpen = true,
            ),
        )

        val inbound = config.array("inbounds").taggedWith("probe-fallback")
        assertEquals(41003, inbound?.getValue("listen_port")?.jsonPrimitive?.int)
        assertEquals("127.0.0.1", inbound?.getValue("listen")?.jsonPrimitive?.content)
        assertEquals("entry-hop", fallbackRule(config).getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `the fallback probe leaves directly when there is no entry hop`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                probe = probe(),
                failOpen = true,
            ),
        )

        assertEquals("direct", fallbackRule(config).getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `no fallback probe exists unless failing open is on`() {
        val raw = SingBoxConfigFactory.androidChainConfig(
            profile(), entry(),
            warp = warp(),
            warpMode = WarpMode.Automatic,
            probe = probe(),
        )

        assertFalse("probe-fallback" in raw)
    }

    @Test
    fun `the fallback port is recovered from the rendered config`() {
        val raw = SingBoxConfigFactory.androidChainConfig(
            profile(),
            warp = warp(),
            warpMode = WarpMode.Automatic,
            probe = probe(),
            failOpen = true,
        )

        assertEquals(probe(), ProbeProxy.fromConfig(raw))
        assertEquals(0, ProbeProxy.fromConfig(SingBoxConfigFactory.androidChainConfig(profile(), warp = warp(), warpMode = WarpMode.Automatic, probe = probe()))?.fallbackPort)
    }

    @Test
    fun `sites that fail open leave by the entry hop beside the relay's learned list`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                learnedWarpPath = "/run/learned-warp.json",
                learnedOpenPath = "/run/learned-open.json",
                failOpen = true,
            ),
        )

        val route = config.getValue("route").jsonObject
        assertEquals("/run/learned-open.json", route.array("rule_set").taggedWith("learned-open")?.getValue("path")?.jsonPrimitive?.content)
        val rule = route.array("rules").map { it.jsonObject }.single { "learned-open" in it.toString() }
        assertEquals("entry-hop", rule.getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `sites that fail open leave directly when there is no entry hop`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                learnedOpenPath = "/run/learned-open.json",
                failOpen = true,
            ),
        )

        val rule = config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
            .single { "learned-open" in it.toString() }
        assertEquals("direct", rule.getValue("outbound").jsonPrimitive.content)
    }

    @Test
    fun `nothing fails open unless the setting is on`() {
        val raw = SingBoxConfigFactory.androidChainConfig(
            profile(), entry(),
            warp = warp(),
            warpMode = WarpMode.Automatic,
            learnedOpenPath = "/run/learned-open.json",
        )

        assertFalse("learned-open" in raw)
    }

    @Test
    fun `failing open never touches the tail or the entry hop's own detour`() {
        val config = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry(),
                warp = warp(),
                warpMode = WarpMode.Automatic,
                learnedWarpPath = "/run/learned-warp.json",
                learnedOpenPath = "/run/learned-open.json",
                failOpen = true,
            ),
        )

        assertEquals("vless-proxy", config.array("endpoints").taggedWith("warp-exit")?.getValue("detour")?.jsonPrimitive?.content)
        assertEquals("entry-hop", config.array("outbounds").taggedWith("vless-proxy")?.getValue("detour")?.jsonPrimitive?.content)
    }

    @Test
    fun `the direct outbound is marked only when a chain without an entry hop fails open`() {
        fun mark(entry: WireGuardEntry? = null, failOpen: Boolean = true, markDirect: Boolean = true) = parse(
            SingBoxConfigFactory.androidChainConfig(
                profile(), entry,
                warpMode = WarpMode.Automatic,
                learnedOpenPath = "/run/learned-open.json",
                failOpen = failOpen,
                markDirect = markDirect,
            ),
        ).array("outbounds").taggedWith("direct")?.get("routing_mark")?.jsonPrimitive?.int

        assertEquals(0x760000, mark())
        assertEquals(null, mark(entry()))
        assertEquals(null, mark(failOpen = false))
        assertEquals(null, mark(markDirect = false))
    }
}

private fun fallbackRule(config: JsonObject): JsonObject =
    config.getValue("route").jsonObject.array("rules").map { it.jsonObject }
        .single { it["inbound"]?.jsonPrimitive?.content == "probe-fallback" }
