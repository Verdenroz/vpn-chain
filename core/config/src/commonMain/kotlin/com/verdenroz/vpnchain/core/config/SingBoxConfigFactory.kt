package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DnsFilter
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
    fun mixedProxyConfig(profile: ChainProfile, clashApi: ClashApi? = null): String = render {
        putInfoLog()
        putExperimental(clashApi, cacheFile = false, cachePath = null)
        putJsonArray("inbounds") {
            addJsonObject {
                put("type", "mixed")
                put("tag", "proxy-in")
                put("listen", "127.0.0.1")
                put("listen_port", profile.localProxyPort)
            }
        }
        putJsonArray("outbounds") {
            addVlessOutbound(profile, tag = "proxy", detour = null, tcpOnly = true)
            addDirectOutbound()
        }
        putJsonObject("route") {
            put("auto_detect_interface", false)
            putJsonArray("rules") { addJsonObject { put("action", "sniff") } }
            put("final", "proxy")
        }
    }

    /**
     * Full TUN chain: TUN → (optional WireGuard entry hop) → VLESS relay.
     * When [ChainProfile.entryHop] is null the chain is relay-only (VLESS
     * dialed directly), which is simpler but exposes the device's IP to the VPS.
     */
    fun androidChainConfig(
        profile: ChainProfile,
        clashApi: ClashApi? = null,
        dnsFilter: DnsFilter = DnsFilter.Off,
        cachePath: String? = null,
    ): String {
        val entry = profile.entryHop
        val filtering = dnsFilter != DnsFilter.Off
        return render {
            putInfoLog()
            // The rule-set is remote, so it needs somewhere to persist between
            // runs; without the cache every connect re-downloads it.
            putExperimental(clashApi, cacheFile = filtering, cachePath = cachePath)
            putJsonObject("dns") {
                putDnsServers(entry)
                if (filtering) putBlocklistRules(dnsFilter)
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    // 10.19.19.1/30 avoids the common Docker 172.16/12 bridge range
                    // (sing-box's usual 172.19.0.1 default collides with br-* bridges).
                    // The ULA address is required too: without it auto_route only
                    // programs an IPv4 default route, so IPv6 traffic skips the tun
                    // entirely and leaks straight out the real interface.
                    putJsonArray("address") { add("10.19.19.1/30"); add("fdfe:dcba:9876::1/126") }
                    // Default TUN MTU is 9000; capped at 1280 to nest inside the entry
                    // WG tunnel (also 1280), else 1400 for VLESS/TLS headroom under 1500.
                    put("mtu", if (entry != null) 1280 else 1400)
                    put("auto_route", true)
                    put("strict_route", true)
                    // gVisor terminates TCP and clamps MSS itself, avoiding kernel PMTU
                    // discovery, which black-holes large packets over a nested tunnel.
                    put("stack", "gvisor")
                }
            }
            if (entry != null) {
                putJsonArray("endpoints") { addWireGuardEndpoint(entry) }
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
                addDirectOutbound()
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                if (filtering) putBlocklistRuleSet(dnsFilter)
                putJsonArray("rules") {
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
                put("final", VLESS_TAG)
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

    /**
     * Downloaded through the chain, never around it — a blocklist fetched over
     * the bare link would announce the app to the network it is hiding from.
     * Binary `.srs` is the compiled form sing-box loads directly.
     */
    private fun JsonObjectBuilder.putBlocklistRuleSet(filter: DnsFilter) {
        putJsonArray("rule_set") {
            blocklistsFor(filter).forEach { list ->
                addJsonObject {
                    put("type", "remote")
                    put("tag", list.tag)
                    put("format", "binary")
                    put("url", list.url)
                    put("download_detour", VLESS_TAG)
                    put("update_interval", "168h")
                }
            }
        }
    }

    private fun blocklistsFor(filter: DnsFilter): List<Blocklist> = when (filter) {
        DnsFilter.Off -> emptyList()
        DnsFilter.Malware -> listOf(THREATS_LIST)
        DnsFilter.AdsAndTrackers -> listOf(THREATS_LIST, GEOSITE_ADS_LIST, ADS_TRACKERS_LIST)
    }

    private fun JsonObjectBuilder.putInfoLog() =
        putJsonObject("log") { put("level", "info"); put("timestamp", true) }

    /**
     * A resolver on the entry peer's internal network — a provider's filtering
     * resolver being the usual case — must be dialed through that peer. Routing
     * it via the relay like the public fallback would make it unreachable, not
     * merely unfiltered.
     */
    private fun JsonObjectBuilder.putDnsServers(entry: WireGuardEntry?) {
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
                    put("detour", VLESS_TAG)
                }
            }
        }
        put("final", if (entry?.dns != null) "dns-entry" else "dns-remote")
        put("strategy", "ipv4_only")
    }

    private fun JsonArrayBuilder.addDirectOutbound() =
        addJsonObject { put("type", "direct"); put("tag", "direct") }

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
        if (detour != null) put("detour", detour)
    }

    private fun JsonArrayBuilder.addWireGuardEndpoint(entry: WireGuardEntry) = addJsonObject {
        put("type", "wireguard")
        put("tag", ENTRY_HOP_TAG)
        put("mtu", 1280)
        putJsonArray("address") { add(entry.address) }
        put("private_key", entry.privateKey)
        putJsonArray("peers") {
            addJsonObject {
                put("address", entry.endpointHost)
                put("port", entry.endpointPort)
                put("public_key", entry.peerPublicKey)
                putJsonArray("allowed_ips") { add("0.0.0.0/0") }
                put("persistent_keepalive_interval", 25)
            }
        }
    }

    private const val VLESS_TAG = "vless-proxy"
    private const val ENTRY_HOP_TAG = "entry-hop"

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
