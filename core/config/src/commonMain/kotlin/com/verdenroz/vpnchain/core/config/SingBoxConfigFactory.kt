package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ProtonWireGuardEntry
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
 *   no entry hop of its own, the real ProtonVPN app fills that role instead.
 * - [androidChainConfig]: full TUN chain with an optional Proton WireGuard
 *   entry hop, used by Android always and by desktop when system-wide TUN is
 *   on. Mirrors config-templates/sing-box-android.template.json.
 */
object SingBoxConfigFactory {
    private val json = Json { prettyPrint = true }

    /** Relay-only config for desktop/CLI (mixed inbound → VLESS). */
    fun mixedProxyConfig(profile: ChainProfile): String = render {
        putInfoLog()
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
     * Full TUN chain: TUN → (optional Proton WireGuard entry) → VLESS relay.
     * When [ChainProfile.protonEntry] is null the chain is relay-only (VLESS
     * dialed directly), which is simpler but exposes the device's IP to the VPS.
     */
    fun androidChainConfig(profile: ChainProfile): String {
        val entry = profile.protonEntry
        return render {
            putInfoLog()
            putJsonObject("dns") { putDnsServers(entry) }
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
                    // Default TUN MTU is 9000; capped at 1280 to nest inside the Proton
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
                    detour = if (entry != null) PROTON_ENTRY_TAG else null,
                    tcpOnly = false,
                )
                addDirectOutbound()
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
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
    private fun JsonObjectBuilder.putInfoLog() =
        putJsonObject("log") { put("level", "info"); put("timestamp", true) }

    /**
     * A Proton NetShield DNS IP must be dialed through the Proton peer itself
     * (it's Proton's own internal resolver) — routing it via the relay like the
     * public fallback would just make it unreachable, not merely unfiltered.
     */
    private fun JsonObjectBuilder.putDnsServers(entry: ProtonWireGuardEntry?) {
        putJsonArray("servers") {
            if (entry?.dns != null) {
                addJsonObject {
                    put("type", "udp")
                    put("tag", "dns-proton")
                    put("server", entry.dns)
                    put("detour", PROTON_ENTRY_TAG)
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
        put("final", if (entry?.dns != null) "dns-proton" else "dns-remote")
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

    private fun JsonArrayBuilder.addWireGuardEndpoint(entry: ProtonWireGuardEntry) = addJsonObject {
        put("type", "wireguard")
        put("tag", PROTON_ENTRY_TAG)
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
    private const val PROTON_ENTRY_TAG = "proton-entry"
}
