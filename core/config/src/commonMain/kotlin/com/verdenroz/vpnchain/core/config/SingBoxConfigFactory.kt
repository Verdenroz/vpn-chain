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
        deviceExit: WireGuardEntry? = null,
        probe: ProbeProxy? = null,
        learnedWarpPath: String? = null,
        learnedDirectPath: String? = null,
        failOpen: Boolean = false,
        connectionLog: Boolean = false,
    ): String {
        val tail = warp.takeIf { warpMode != WarpMode.Off }
        val learnedPath = learnedWarpPath.takeIf { tail != null && warpMode == WarpMode.Automatic }
        val opensDirect = failOpen && warpMode == WarpMode.Automatic
        // No entry hop here, so only sites the user sent to the bare link leave the relay.
        val directPath = learnedDirectPath.takeIf { opensDirect }
        return render {
            putLog(connectionLog)
            putExperimental(clashApi, cacheFile = false, cachePath = null)
            // The relay resolves its own names; WARP and direct would otherwise ask the local resolver.
            if (tail != null || opensDirect || deviceExit != null) {
                putJsonObject("dns") { putDnsServers(entry = null, remoteDetour = "proxy", exit = deviceExit) }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "mixed")
                    put("tag", "proxy-in")
                    // No login: every local user can send traffic through the chain, so never bind wider.
                    put("listen", "127.0.0.1")
                    put("listen_port", profile.localProxyPort)
                }
                probe?.let { addProbeInbounds(it, withWarp = tail != null, withFallback = false) }
            }
            if (tail != null || deviceExit != null) {
                putJsonArray("endpoints") {
                    tail?.let { addWarpEndpoint(it, detour = "proxy") }
                    deviceExit?.let { addDeviceExitEndpoint(it, detour = "proxy") }
                }
            }
            putJsonArray("outbounds") {
                addVlessOutbound(profile, tag = "proxy", detour = null, tcpOnly = true)
                addDirectOutbound()
            }
            putJsonObject("route") {
                put("auto_detect_interface", false)
                putRuleSets(DnsFilter.Off, learnedPath, learnedOpenPath = null, learnedDirectPath = directPath)
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    probe?.let { addProbeRules(withWarp = tail != null, relayTag = if (deviceExit != null) DEVICE_EXIT_TAG else "proxy", fallbackTag = null) }
                    learnedPath?.let { addLearnedRule(LEARNED_WARP_TAG, WARP_TAG) }
                    directPath?.let { addLearnedRule(LEARNED_DIRECT_TAG, DIRECT_TAG) }
                }
                put("final", finalOutbound(tail != null && warpMode == WarpMode.AllTraffic, deviceExit != null, "proxy"))
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
        deviceExit: WireGuardEntry? = null,
        allowLocalNetworks: Boolean = false,
        logPath: String? = null,
        probe: ProbeProxy? = null,
        learnedWarpPath: String? = null,
        learnedOpenPath: String? = null,
        learnedDirectPath: String? = null,
        failOpen: Boolean = false,
        markDirect: Boolean = false,
        connectionLog: Boolean = false,
    ): String {
        val filtering = dnsFilter != DnsFilter.Off
        // No credentials means no tail, whatever the mode says: registration
        // can fail on a network that blocks Cloudflare, and the chain still has
        // to come up — one hop shorter, not not at all.
        val tail = warp.takeIf { warpMode != WarpMode.Off }
        val learnedPath = learnedWarpPath.takeIf { tail != null && warpMode == WarpMode.Automatic }
        val opensOutside = failOpen && warpMode == WarpMode.Automatic
        // Past the relay, not through it. Each path takes only the sites the user sent there:
        // the entry hop the ones it was probed and found to reach, the bare link the rest.
        val openPath = learnedOpenPath.takeIf { opensOutside && entry != null }
        val directPath = learnedDirectPath.takeIf { opensOutside }
        val probesFallback = opensOutside && entry != null
        val directMark = FAIL_OPEN_MARK.takeIf { markDirect && directPath != null }
        return render {
            putLog(connectionLog, logPath)
            // The rule-set is remote, so it needs somewhere to persist between
            // runs; without the cache every connect re-downloads it.
            putExperimental(clashApi, cacheFile = filtering, cachePath = cachePath)
            putJsonObject("dns") {
                putDnsServers(entry, exit = deviceExit)
                if (filtering) putBlocklistRules(dnsFilter)
            }
            putJsonArray("inbounds") {
                // sing-box defaults to 9000. 1280 (or a lower entry or exit MTU) nests inside a
                // WireGuard hop; otherwise 1400 leaves VLESS/TLS headroom under 1500.
                addTunInbound(
                    mtu = if (entry != null || tail != null || deviceExit != null) {
                        listOfNotNull(entry?.mtu, deviceExit?.mtu, WIREGUARD_MTU).min()
                    } else {
                        1400
                    },
                    allowLocalNetworks = allowLocalNetworks,
                )
                probe?.let { addProbeInbounds(it, withWarp = tail != null, withFallback = probesFallback) }
            }
            if (entry != null || tail != null || deviceExit != null) {
                putJsonArray("endpoints") {
                    entry?.let { addWireGuardEndpoint(it) }
                    // Dialled through the relay, so the tail is the last hop of
                    // the chain rather than a second path around it.
                    tail?.let { addWarpEndpoint(it, detour = VLESS_TAG) }
                    deviceExit?.let { addDeviceExitEndpoint(it, detour = VLESS_TAG) }
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
                putRuleSets(dnsFilter, learnedPath, openPath, learnedDirectPath = directPath)
                putJsonArray("rules") {
                    addTunBaseRules()
                    probe?.let {
                        addProbeRules(
                            withWarp = tail != null,
                            relayTag = if (deviceExit != null) DEVICE_EXIT_TAG else VLESS_TAG,
                            fallbackTag = ENTRY_HOP_TAG.takeIf { probesFallback },
                        )
                    }
                    learnedPath?.let { addLearnedRule(LEARNED_WARP_TAG, WARP_TAG) }
                    openPath?.let { addLearnedRule(LEARNED_OPEN_TAG, ENTRY_HOP_TAG) }
                    directPath?.let { addLearnedRule(LEARNED_DIRECT_TAG, DIRECT_TAG) }
                }
                put("final", finalOutbound(tail != null && warpMode == WarpMode.AllTraffic, deviceExit != null, VLESS_TAG))
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
        connectionLog: Boolean = false,
    ): String {
        val filtering = dnsFilter != DnsFilter.Off
        return render {
            putLog(connectionLog, logPath)
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
                putRuleSets(dnsFilter, null, null, null, detour = ENTRY_HOP_TAG)
                putJsonArray("rules") { addTunBaseRules() }
                put("final", ENTRY_HOP_TAG)
            }
        }
    }

    private fun finalOutbound(warpCarriesAll: Boolean, hasDeviceExit: Boolean, relayTag: String): String = when {
        warpCarriesAll -> WARP_TAG
        hasDeviceExit -> DEVICE_EXIT_TAG
        else -> relayTag
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

    // Info records every connection and DNS answer, so it is opt-in.
    private fun JsonObjectBuilder.putLog(connectionLog: Boolean, output: String? = null) =
        putJsonObject("log") {
            put("level", if (connectionLog) "info" else "warn")
            put("timestamp", true)
            // Under systemd the app can't read the process's stdout, so sing-box
            // writes the log itself and the app tails that file.
            if (output != null) put("output", output)
        }

    /** Whether a rendered config routes by a learned rule-set; false for one that cannot be read. */
    fun routesLearnedSites(configJson: String): Boolean = configRoutesLearnedSites(configJson)

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
        put("connect_timeout", if (detour == null) DIRECT_DIAL_TIMEOUT else DETOURED_DIAL_TIMEOUT)
        if (detour != null) put("detour", detour)
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

    const val DIRECT_TAG = "direct"

    /**
     * The packet mark on the `direct` outbound that the desktop kill switch
     * accepts. Chosen to stay clear of marks other tooling claims: sing-box's
     * auto_redirect (0x2023 to 0x2025), WireGuard quick (51820), Kubernetes
     * (0x4000, 0x8000) and Tailscale (0x40000, 0x80000 under mask 0xff0000).
     */
    const val FAIL_OPEN_MARK = 0x760000

    private const val DIRECT_DIAL_TIMEOUT = "3s"

    // WireGuard retries a lost handshake after 5s and the SYN waiting on it resends at 7s.
    private const val DETOURED_DIAL_TIMEOUT = "10s"

    /**
     * Bumped whenever a config this build renders would route differently from
     * an older one's. A relay outlives the app under systemd, so on adoption a
     * config stamped with another schema is rendered again rather than reused.
     */
    const val RENDER_SCHEMA = 5
}
