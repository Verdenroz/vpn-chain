package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds sing-box client configs from a [ChainProfile]. Two shapes:
 * - [mixedProxyConfig]: relay-only local mixed inbound → VLESS (desktop/CLI);
 *   no entry hop of its own, an external VPN app fills that role instead.
 * - [androidChainConfig]: full TUN chain with an optional WireGuard entry hop
 *   from any provider, used by Android always and by desktop when system-wide
 *   TUN is on. Mirrors config-templates/sing-box-android.template.json.
 */
object SingBoxConfigFactory {
    private val json = Json { prettyPrint = true }

    /** Relay-only config for desktop/CLI (mixed inbound → VLESS). */
    fun mixedProxyConfig(
        profile: ChainProfile,
        clashApi: ClashApi? = null,
        warp: WarpExit? = null,
        warpMode: WarpMode = WarpMode.Off,
        probe: ProbeProxy? = null,
        learnedWarpPath: String? = null,
        learnedDirectPath: String? = null,
        failOpen: Boolean = false,
    ): String {
        val tail = warp.takeIf { warpMode != WarpMode.Off }
        val learnedPath = learnedWarpPath.takeIf { tail != null && warpMode == WarpMode.Automatic }
        val opensDirect = failOpen && warpMode == WarpMode.Automatic
        // No entry hop here, so only sites the user sent to the bare link leave the relay.
        val openPath = learnedDirectPath.takeIf { opensDirect }
        return render {
            putInfoLog()
            putExperimental(clashApi, cacheFile = false, cachePath = null)
            // The relay resolves its own names; WARP and direct would otherwise ask the local resolver.
            if (tail != null || opensDirect) putJsonObject("dns") { putDnsServers(entry = null, remoteDetour = "proxy") }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "mixed")
                    put("tag", "proxy-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", profile.localProxyPort)
                }
                probe?.let { addProbeInbounds(it, withWarp = tail != null, withFallback = false) }
            }
            if (tail != null) {
                putJsonArray("endpoints") { addWarpEndpoint(tail, detour = "proxy") }
            }
            putJsonArray("outbounds") {
                addVlessOutbound(profile, tag = "proxy", detour = null, tcpOnly = true)
                addDirectOutbound()
            }
            putJsonObject("route") {
                put("auto_detect_interface", false)
                putRuleSets(DnsFilter.Off, learnedPath, openPath)
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    probe?.let { addProbeRules(withWarp = tail != null, relayTag = "proxy", fallbackTag = null) }
                    learnedPath?.let { addLearnedRule(LEARNED_WARP_TAG, WARP_TAG) }
                    openPath?.let { addLearnedRule(LEARNED_OPEN_TAG, DIRECT_TAG) }
                }
                put("final", if (tail != null && warpMode == WarpMode.AllTraffic) WARP_TAG else "proxy")
            }
        }
    }

    /**
     * Full TUN chain: TUN → (optional WireGuard entry hop) → VLESS relay.
     * When [entry] is null the chain is relay-only (VLESS
     * dialed directly), which is simpler but exposes the device's IP to the VPS.
     */
    fun androidChainConfig(
        profile: ChainProfile,
        entry: WireGuardEntry? = null,
        clashApi: ClashApi? = null,
        dnsFilter: DnsFilter = DnsFilter.Off,
        cachePath: String? = null,
        warp: WarpExit? = null,
        warpMode: WarpMode = WarpMode.Off,
        allowLocalNetworks: Boolean = false,
        logPath: String? = null,
        probe: ProbeProxy? = null,
        learnedWarpPath: String? = null,
        learnedOpenPath: String? = null,
        learnedDirectPath: String? = null,
        failOpen: Boolean = false,
        markDirect: Boolean = false,
    ): String {
        val filtering = dnsFilter != DnsFilter.Off
        // No credentials means no tail, whatever the mode says: registration
        // can fail on a network that blocks Cloudflare, and the chain still has
        // to come up — one hop shorter, not not at all.
        val tail = warp.takeIf { warpMode != WarpMode.Off }
        val learnedPath = learnedWarpPath.takeIf { tail != null && warpMode == WarpMode.Automatic }
        val opensOutside = failOpen && warpMode == WarpMode.Automatic
        // Past the relay, not through it. The entry hop takes what it was probed and found
        // to reach; without one, the bare link takes only sites the user sent there.
        val openPath = (if (entry != null) learnedOpenPath else learnedDirectPath).takeIf { opensOutside }
        val openTag = if (entry != null) ENTRY_HOP_TAG else DIRECT_TAG
        val probesFallback = opensOutside && entry != null
        val directMark = FAIL_OPEN_MARK.takeIf { markDirect && entry == null && openPath != null }
        return render {
            putInfoLog(logPath)
            // The rule-set is remote, so it needs somewhere to persist between
            // runs; without the cache every connect re-downloads it.
            putExperimental(clashApi, cacheFile = filtering, cachePath = cachePath)
            putJsonObject("dns") {
                putDnsServers(entry)
                if (filtering) putBlocklistRules(dnsFilter)
            }
            putJsonArray("inbounds") {
                // sing-box defaults to 9000. 1280 (or a lower entry MTU) nests inside the entry
                // hop or a 1280 WARP tail; otherwise 1400 leaves VLESS/TLS headroom under 1500.
                addTunInbound(
                    mtu = if (entry != null || tail != null) (entry?.mtu?.coerceAtMost(1280) ?: 1280) else 1400,
                    allowLocalNetworks = allowLocalNetworks,
                )
                probe?.let { addProbeInbounds(it, withWarp = tail != null, withFallback = probesFallback) }
            }
            if (entry != null || tail != null) {
                putJsonArray("endpoints") {
                    entry?.let { addWireGuardEndpoint(it) }
                    // Dialled through the relay, so the tail is the last hop of
                    // the chain rather than a second path around it.
                    tail?.let { addWarpEndpoint(it, detour = VLESS_TAG) }
                }
            }
            putJsonArray("outbounds") {
                // TUN captures all traffic incl. UDP, so the relay must carry UDP
                // (via xudp) — not tcp-only like the proxy config.
                addVlessOutbound(
                    profile,
                    tag = VLESS_TAG,
                    detour = if (entry != null) ENTRY_HOP_TAG else null,
                    tcpOnly = false,
                )
                addDirectOutbound(directMark)
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                putRuleSets(dnsFilter, learnedPath, openPath)
                putJsonArray("rules") {
                    addTunBaseRules()
                    probe?.let {
                        addProbeRules(
                            withWarp = tail != null,
                            relayTag = VLESS_TAG,
                            fallbackTag = ENTRY_HOP_TAG.takeIf { probesFallback },
                        )
                    }
                    learnedPath?.let { addLearnedRule(LEARNED_WARP_TAG, WARP_TAG) }
                    openPath?.let { addLearnedRule(LEARNED_OPEN_TAG, openTag) }
                }
                put(
                    "final",
                    if (tail != null && warpMode == WarpMode.AllTraffic) WARP_TAG else VLESS_TAG,
                )
            }
        }
    }

    /**
     * A WireGuard peer on its own: TUN → peer, with no relay. The WARP tail is
     * defined as dialled through the relay, so there is none here.
     */
    fun wireGuardConfig(
        entry: WireGuardEntry,
        clashApi: ClashApi? = null,
        dnsFilter: DnsFilter = DnsFilter.Off,
        cachePath: String? = null,
        allowLocalNetworks: Boolean = false,
        logPath: String? = null,
    ): String {
        val filtering = dnsFilter != DnsFilter.Off
        return render {
            putInfoLog(logPath)
            putExperimental(clashApi, cacheFile = filtering, cachePath = cachePath)
            putJsonObject("dns") {
                putDnsServers(entry, remoteDetour = ENTRY_HOP_TAG)
                if (filtering) putBlocklistRules(dnsFilter)
            }
            putJsonArray("inbounds") {
                addTunInbound(mtu = entry.mtu ?: 1280, allowLocalNetworks = allowLocalNetworks)
            }
            putJsonArray("endpoints") {
                val v6 = entry.addresses().any { ':' in it }
                addWireGuardEndpoint(entry, allowedIps = if (v6) listOf("0.0.0.0/0", "::/0") else listOf("0.0.0.0/0"))
            }
            putJsonArray("outbounds") { addDirectOutbound() }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                putRuleSets(dnsFilter, learnedWarpPath = null, learnedOpenPath = null, detour = ENTRY_HOP_TAG)
                putJsonArray("rules") { addTunBaseRules() }
                put("final", ENTRY_HOP_TAG)
            }
        }
    }

    private fun render(build: JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(JsonElement.serializer(), buildJsonObject(build))

    // info (not warn) so the Logs screen shows connection/handshake activity.
    /**
     * sing-box allows one `experimental` block, so the clash API and the
     * rule-set cache have to be written together or the second wins.
     *
     * The API is read-only on our side, but the same one can close connections
     * and switch outbounds — hence the secret, and the loopback-only bind.
     */
    private fun JsonObjectBuilder.putExperimental(
        clashApi: ClashApi?,
        cacheFile: Boolean,
        cachePath: String?,
    ) {
        if (clashApi == null && !cacheFile) return
        putJsonObject("experimental") {
            if (clashApi != null) {
                putJsonObject("clash_api") {
                    put("external_controller", clashApi.externalController)
                    put("secret", clashApi.secret)
                }
            }
            if (cacheFile) {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    // Relative by default, which resolves against a working
                    // directory we don't control — at login it can be `/`.
                    if (cachePath != null) put("path", cachePath)
                }
            }
        }
    }

    /**
     * Upstream filtering's job, done client-side: reject the lookup rather than
     * answer with an address. `refused` fails callers fast instead of leaving
     * them to time out on a black-holed connection. One rule covers every
     * rule-set of the level — a name is refused if any of them lists it.
     */
    private fun JsonObjectBuilder.putBlocklistRules(filter: DnsFilter) {
        putJsonArray("rules") {
            addJsonObject {
                putJsonArray("rule_set") { blocklistsFor(filter).forEach { add(it.tag) } }
                put("action", "reject")
                put("method", "default")
            }
        }
    }

    private fun JsonObjectBuilder.putRuleSets(
        filter: DnsFilter,
        learnedWarpPath: String?,
        learnedOpenPath: String?,
        detour: String = VLESS_TAG,
    ) {
        val blocklists = blocklistsFor(filter)
        if (blocklists.isEmpty() && learnedWarpPath == null && learnedOpenPath == null) return
        putJsonArray("rule_set") {
            blocklists.forEach { addBlocklistRuleSet(it, detour) }
            learnedWarpPath?.let { addLearnedRuleSet(LEARNED_WARP_TAG, it) }
            learnedOpenPath?.let { addLearnedRuleSet(LEARNED_OPEN_TAG, it) }
        }
    }

    private fun JsonArrayBuilder.addLearnedRuleSet(tag: String, path: String) = addJsonObject {
        put("type", "local")
        put("tag", tag)
        put("format", "source")
        put("path", path)
    }

    /**
     * Downloaded through the chain, never around it — a blocklist fetched over
     * the bare link would announce the app to the network it is hiding from.
     * Binary `.srs` is the compiled form sing-box loads directly.
     */
    private fun JsonArrayBuilder.addBlocklistRuleSet(list: Blocklist, detour: String) = addJsonObject {
        put("type", "remote")
        put("tag", list.tag)
        put("format", "binary")
        put("url", list.url)
        put("download_detour", detour)
        put("update_interval", "168h")
    }

    private fun blocklistsFor(filter: DnsFilter): List<Blocklist> = when (filter) {
        DnsFilter.Off -> emptyList()
        DnsFilter.Malware -> listOf(THREATS_LIST)
        DnsFilter.AdsAndTrackers -> listOf(THREATS_LIST, GEOSITE_ADS_LIST, ADS_TRACKERS_LIST)
    }

    private fun JsonObjectBuilder.putInfoLog(output: String? = null) =
        putJsonObject("log") {
            put("level", "info")
            put("timestamp", true)
            // Under systemd the app can't read the process's stdout, so sing-box
            // writes the log itself and the app tails that file.
            if (output != null) put("output", output)
        }

    /**
     * A resolver on the entry peer's internal network — a provider's filtering
     * resolver being the usual case — must be dialed through that peer. Routing
     * it via the relay like the public fallback would make it unreachable, not
     * merely unfiltered.
     */
    private fun JsonObjectBuilder.putDnsServers(entry: WireGuardEntry?, remoteDetour: String = VLESS_TAG) {
        putJsonArray("servers") {
            if (entry?.dns != null) {
                addJsonObject {
                    put("type", "udp")
                    put("tag", "dns-entry")
                    put("server", entry.dns)
                    put("detour", ENTRY_HOP_TAG)
                }
            } else {
                addJsonObject {
                    put("type", "https")
                    put("tag", "dns-remote")
                    put("server", "1.1.1.1")
                    put("detour", remoteDetour)
                }
            }
        }
        put("final", if (entry?.dns != null) "dns-entry" else "dns-remote")
        put("strategy", "ipv4_only")
    }

    private fun JsonArrayBuilder.addTunInbound(mtu: Int, allowLocalNetworks: Boolean) = addJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        // 10.19.19.1/30 avoids the common Docker 172.16/12 bridge range
        // (sing-box's usual 172.19.0.1 default collides with br-* bridges).
        // The ULA address is required too: without it auto_route only
        // programs an IPv4 default route, so IPv6 traffic skips the tun
        // entirely and leaks straight out the real interface.
        putJsonArray("address") { add("10.19.19.1/30"); add("fdfe:dcba:9876::1/126") }
        put("mtu", mtu)
        put("auto_route", true)
        put("strict_route", true)
        // auto_route otherwise pulls bridge and LAN destinations into the
        // tun, so Docker containers and local hosts never see a reply.
        if (allowLocalNetworks) {
            putJsonArray("route_exclude_address") {
                LOCAL_ROUTE_EXCLUDES.forEach { add(it) }
            }
        }
        // gVisor terminates TCP and clamps MSS itself, avoiding kernel PMTU
        // discovery, which black-holes large packets over a nested tunnel.
        put("stack", "gvisor")
    }

    private fun JsonArrayBuilder.addTunBaseRules() {
        addJsonObject { put("action", "sniff") }
        addJsonObject { put("protocol", "dns"); put("action", "hijack-dns") }
        // Reject QUIC so browsers fall back to TCP; QUIC over a small-MTU
        // tunnel blackholes and stalls page loads.
        addJsonObject {
            put("network", "udp")
            put("port", 443)
            put("action", "reject")
        }
    }

    private fun WireGuardEntry.addresses(): List<String> = address.split(',').map(String::trim)

    private fun JsonArrayBuilder.addDirectOutbound(mark: Int? = null) = addJsonObject {
        put("type", "direct")
        put("tag", DIRECT_TAG)
        if (mark != null) put("routing_mark", mark)
    }

    private fun JsonArrayBuilder.addVlessOutbound(
        profile: ChainProfile,
        tag: String,
        detour: String?,
        tcpOnly: Boolean,
    ) = addJsonObject {
        put("type", "vless")
        put("tag", tag)
        put("server", profile.vpsIp)
        put("server_port", profile.serverPort)
        put("uuid", profile.vlessUuid)
        put("flow", "xtls-rprx-vision")
        // tcp-only for the SOCKS proxy; omitted for TUN so UDP is carried via xudp.
        if (tcpOnly) put("network", "tcp")
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", profile.sni)
            putJsonObject("utls") { put("enabled", true); put("fingerprint", "chrome") }
            putJsonObject("reality") {
                put("enabled", true)
                put("public_key", profile.realityPublicKey)
                put("short_id", profile.shortId)
            }
        }
        put("packet_encoding", "xudp")
        put("connect_timeout", "3s")
        if (detour != null) put("detour", detour)
    }

    private fun JsonArrayBuilder.addWireGuardEndpoint(
        entry: WireGuardEntry,
        allowedIps: List<String> = listOf("0.0.0.0/0"),
    ) = addJsonObject {
        put("type", "wireguard")
        put("tag", ENTRY_HOP_TAG)
        put("mtu", entry.mtu ?: 1280)
        putJsonArray("address") { entry.addresses().forEach { add(it) } }
        put("private_key", entry.privateKey)
        putJsonArray("peers") {
            addJsonObject {
                put("address", entry.endpointHost)
                put("port", entry.endpointPort)
                put("public_key", entry.peerPublicKey)
                entry.presharedKey?.let { put("pre_shared_key", it) }
                putJsonArray("allowed_ips") { allowedIps.forEach { add(it) } }
                put("persistent_keepalive_interval", entry.keepaliveSeconds ?: 25)
            }
        }
    }

    /**
     * The WARP tail: a WireGuard peer dialled *through* [detour], so packets
     * leave Cloudflare's network instead of the relay's own datacenter address.
     * Nothing upstream changes — the entry hop and relay still carry the
     * traffic, and Cloudflare sees the relay's address, never the device's.
     */
    private fun JsonArrayBuilder.addWarpEndpoint(warp: WarpExit, detour: String) = addJsonObject {
        put("type", "wireguard")
        put("tag", WARP_TAG)
        put("mtu", 1280)
        putJsonArray("address") { add(warp.addressV4); add(warp.addressV6) }
        put("private_key", warp.privateKey)
        // Without this the handshake is dialled on the bare link, where the kill
        // switch rejects it outright — and where it would bypass the chain anyway.
        put("detour", detour)
        putJsonArray("peers") {
            addJsonObject {
                put("address", warp.endpointHost)
                put("port", warp.endpointPort)
                put("public_key", warp.peerPublicKey)
                putJsonArray("allowed_ips") { add("0.0.0.0/0"); add("::/0") }
                put("persistent_keepalive_interval", 25)
            }
        }
    }

    /** Loopback only: these accept any destination, so they must never face the network. */
    private fun JsonArrayBuilder.addProbeInbounds(probe: ProbeProxy, withWarp: Boolean, withFallback: Boolean) {
        addProbeInbound(ProbeProxy.RELAY_TAG, probe.relayPort, probe)
        if (withWarp) addProbeInbound(ProbeProxy.WARP_TAG, probe.warpPort, probe)
        if (withFallback) addProbeInbound(ProbeProxy.FALLBACK_TAG, probe.fallbackPort, probe)
    }

    private fun JsonArrayBuilder.addProbeInbound(tag: String, port: Int, probe: ProbeProxy) =
        addJsonObject {
            put("type", "mixed")
            put("tag", tag)
            put("listen", "127.0.0.1")
            put("listen_port", port)
            putJsonArray("users") {
                addJsonObject {
                    put("username", probe.username)
                    put("password", probe.password)
                }
            }
        }

    /** Ahead of the domain rules, so a probe exits where it asked to whatever the list says. */
    private fun JsonArrayBuilder.addProbeRules(withWarp: Boolean, relayTag: String, fallbackTag: String?) {
        addInboundRoute(ProbeProxy.RELAY_TAG, relayTag)
        if (withWarp) addInboundRoute(ProbeProxy.WARP_TAG, WARP_TAG)
        fallbackTag?.let { addInboundRoute(ProbeProxy.FALLBACK_TAG, it) }
    }

    private fun JsonArrayBuilder.addInboundRoute(inbound: String, outbound: String) = addJsonObject {
        put("inbound", inbound)
        put("action", "route")
        put("outbound", outbound)
    }

    private fun JsonArrayBuilder.addLearnedRule(ruleSet: String, outbound: String) = addJsonObject {
        putJsonArray("rule_set") { add(ruleSet) }
        put("action", "route")
        put("outbound", outbound)
    }

    private val LOCAL_ROUTE_EXCLUDES =
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")

    private const val VLESS_TAG = "vless-proxy"
    private const val ENTRY_HOP_TAG = "entry-hop"
    private const val WARP_TAG = "warp-exit"
    private const val LEARNED_WARP_TAG = "learned-warp"
    private const val LEARNED_OPEN_TAG = "learned-open"
    const val DIRECT_TAG = "direct"

    /**
     * The packet mark on the `direct` outbound that the desktop kill switch
     * accepts. Chosen to stay clear of marks other tooling claims: sing-box's
     * auto_redirect (0x2023 to 0x2025), WireGuard quick (51820), Kubernetes
     * (0x4000, 0x8000) and Tailscale (0x40000, 0x80000 under mask 0xff0000).
     */
    const val FAIL_OPEN_MARK = 0x760000

    private data class Blocklist(val tag: String, val url: String)

    /**
     * These files decide what the chain refuses to resolve, so each comes from
     * a party we already trust: sing-box's own geosite mirror (the upstream
     * that ships the binary we run), and our own `blocklists` release, where
     * CI converts hagezi's lists with a pinned sing-box — no third-party
     * conversion repo in between. See `.github/workflows/blocklists.yml`.
     */
    private const val BLOCKLISTS_RELEASE =
        "https://github.com/Verdenroz/vpn-chain/releases/download/blocklists"

    /** hagezi TIF medium: malware, phishing, and scam domains. */
    private val THREATS_LIST = Blocklist("blocklist-threats", "$BLOCKLISTS_RELEASE/threats.srs")

    /** hagezi Pro++: ads, trackers, telemetry. 99% on d3ward's adblock test. */
    private val ADS_TRACKERS_LIST =
        Blocklist("blocklist-ads-trackers", "$BLOCKLISTS_RELEASE/ads-trackers.srs")

    /**
     * v2fly's ad list, kept alongside hagezi's: it blocks apex domains hagezi
     * deliberately spares (e.g. `doubleclick.net`), and it costs 8 KB.
     */
    private val GEOSITE_ADS_LIST = Blocklist(
        "blocklist-ads",
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
    )
}
