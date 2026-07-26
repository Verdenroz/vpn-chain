package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shaped like the real rendered configs, pretty-printed the same way. */
private val TUN_WITH_ENTRY = """
    {
        "inbounds": [
            {
                "type": "tun",
                "tag": "tun-in",
                "address": [
                    "10.19.19.1/30",
                    "fdfe:dcba:9876::1/126"
                ],
                "mtu": 1280
            }
        ],
        "endpoints": [
            {
                "type": "wireguard",
                "tag": "entry-hop",
                "peers": [
                    {
                        "address": "146.70.198.34",
                        "port": 51820
                    }
                ]
            }
        ],
        "outbounds": [
            {
                "type": "vless",
                "server": "89.127.235.38",
                "server_port": 443
            }
        ]
    }
""".trimIndent()

/** An entry-side DNS server also has a bare "server": "<ip>" field, and — being
 *  in the "dns" block — renders before "outbounds" in the real config. */
private val TUN_WITH_ENTRY_DNS = """
    {
        "dns": {
            "servers": [
                { "type": "udp", "tag": "dns-entry", "server": "10.2.0.1", "detour": "entry-hop" }
            ]
        },
        "inbounds": [
            {
                "type": "tun",
                "tag": "tun-in",
                "address": [
                    "10.19.19.1/30"
                ],
                "mtu": 1280
            }
        ],
        "endpoints": [
            {
                "type": "wireguard",
                "tag": "entry-hop",
                "peers": [
                    {
                        "address": "146.70.198.34",
                        "port": 51820
                    }
                ]
            }
        ],
        "outbounds": [
            {
                "type": "vless",
                "server": "89.127.235.38",
                "server_port": 443
            }
        ]
    }
""".trimIndent()

private val PROXY_ONLY = """
    {
        "inbounds": [
            {
                "type": "mixed",
                "listen": "127.0.0.1",
                "listen_port": 1081
            }
        ],
        "outbounds": [
            {
                "type": "vless",
                "server": "89.127.235.38",
                "server_port": 443
            }
        ]
    }
""".trimIndent()

/** Single-hop with a WARP tail: a WireGuard endpoint, but not an entry hop. */
private val TUN_WARP_ONLY = """
    {
        "inbounds": [
            { "type": "tun", "tag": "tun-in", "mtu": 1280 }
        ],
        "endpoints": [
            {
                "type": "wireguard",
                "tag": "warp-exit",
                "detour": "vless-proxy",
                "peers": [
                    {
                        "address": "162.159.192.1",
                        "port": 2408
                    }
                ]
            }
        ],
        "outbounds": [
            {
                "type": "vless",
                "tag": "vless-proxy",
                "server": "89.127.235.38",
                "server_port": 443
            }
        ]
    }
""".trimIndent()

/** Both hops: the entry peer is dialled directly, the tail through the relay. */
private val TUN_ENTRY_AND_WARP = """
    {
        "inbounds": [
            { "type": "tun", "tag": "tun-in", "mtu": 1280 }
        ],
        "endpoints": [
            {
                "type": "wireguard",
                "tag": "entry-hop",
                "peers": [
                    { "address": "146.70.198.34", "port": 51820 }
                ]
            },
            {
                "type": "wireguard",
                "tag": "warp-exit",
                "detour": "vless-proxy",
                "peers": [
                    { "address": "162.159.192.1", "port": 2408 }
                ]
            }
        ],
        "outbounds": [
            {
                "type": "vless",
                "tag": "vless-proxy",
                "server": "89.127.235.38",
                "server_port": 443
            }
        ]
    }
""".trimIndent()

class RelayConfigTest {

    @Test
    fun `recognises a tun config and a proxy config`() {
        assertTrue(RelayConfig.isTun(TUN_WITH_ENTRY))
        assertFalse(RelayConfig.isTun(PROXY_ONLY))
    }

    @Test
    fun `detects the wireguard entry hop only when one is declared`() {
        assertTrue(RelayConfig.hasWireGuardEntry(TUN_WITH_ENTRY))
        assertFalse(RelayConfig.hasWireGuardEntry(PROXY_ONLY))
    }

    /** Regression: the WARP tail is a WireGuard endpoint too. Counting it as an
     *  entry hop makes the readout claim a hop the chain doesn't have — and
     *  makes the controller think the firewall is its own to own. */
    @Test
    fun `a warp tail alone is not an entry hop`() {
        assertFalse(RelayConfig.hasWireGuardEntry(TUN_WARP_ONLY))
        assertTrue(RelayConfig.hasWireGuardEntry(TUN_ENTRY_AND_WARP))
    }

    /**
     * The tail is dialled through the relay, so its packets are already inside
     * the tunnel. Exempting Cloudflare's anycast address would open a hole
     * nothing needs — and, worse, one that stays open if sing-box dies.
     */
    @Test
    fun `the warp endpoint is never exempted from the kill switch`() {
        assertEquals(listOf("89.127.235.38"), RelayConfig.exemptIps(TUN_WARP_ONLY))
        assertEquals(
            listOf("89.127.235.38", "146.70.198.34"),
            RelayConfig.exemptIps(TUN_ENTRY_AND_WARP),
        )
    }

    @Test
    fun `reads the listen port and falls back when there is no listener`() {
        assertEquals(1081, RelayConfig.proxyPort(PROXY_ONLY, default = 1080))
        // A TUN config has no inbound listener at all — the default must survive.
        assertEquals(1080, RelayConfig.proxyPort(TUN_WITH_ENTRY, default = 1080))
    }

    /**
     * The kill switch blocks everything except these. Missing the entry peer
     * here is what leaves sing-box unable to reach its own entry hop, so the
     * machine ends up fail-closed against a tunnel that can never come up.
     */
    @Test
    fun `exempts both the vps and the entry peer endpoint`() {
        val exempt = RelayConfig.exemptIps(TUN_WITH_ENTRY)

        assertEquals(listOf("89.127.235.38", "146.70.198.34"), exempt)
    }

    @Test
    fun `exempts only the vps when there is no entry hop`() {
        assertEquals(listOf("89.127.235.38"), RelayConfig.exemptIps(PROXY_ONLY))
    }

    @Test
    fun `does not mistake the tun interface address for a peer endpoint`() {
        // "address" appears as an array on the TUN inbound; only the peer form
        // ("address": "x", "port": n) may be treated as an endpoint.
        assertFalse(RelayConfig.exemptIps(TUN_WITH_ENTRY).any { it.startsWith("10.19") })
    }

    @Test
    fun `returns nothing when the config names no addresses`() {
        assertTrue(RelayConfig.exemptIps("""{"outbounds":[]}""").isEmpty())
    }

    /** Regression: an entry DNS server's bare "server" field rendering
     *  ahead of the real outbound must not steal the VPS's exemption slot —
     *  that strands the machine with the actual relay firewalled off. */
    @Test
    fun `exempts the vps, not the entry dns server, when both are present`() {
        val exempt = RelayConfig.exemptIps(TUN_WITH_ENTRY_DNS)

        assertEquals(listOf("89.127.235.38", "146.70.198.34"), exempt)
        assertFalse("10.2.0.1" in exempt)
    }
}

class RelaySessionTest {

    @Test
    fun `round-trips through its persisted form`() {
        val session = RelaySession(
            tun = true,
            entry = true,
            killSwitch = true,
            killSwitchEnabled = true,
        )

        assertEquals(session, RelaySession.parse(session.format()))
    }

    @Test
    fun `keeps the enabled flag apart from whether it actually engaged`() {
        val wanted = RelaySession(
            tun = true,
            entry = true,
            killSwitch = false,
            killSwitchEnabled = true,
        )

        val parsed = RelaySession.parse(wanted.format())

        // "asked for, didn't get" is what tells the panel to say the helper is
        // missing rather than that the user turned it off.
        assertEquals(false, parsed?.killSwitch)
        assertEquals(true, parsed?.killSwitchEnabled)
    }

    @Test
    fun `refuses a truncated file rather than defaulting its fields`() {
        assertEquals(null, RelaySession.parse("tun=true\n"))
        assertEquals(null, RelaySession.parse(""))
        assertEquals(null, RelaySession.parse("garbage"))
    }

    @Test
    fun `reads an older file that predates the enabled flag`() {
        val parsed = RelaySession.parse("tun=true\nentry=false\nkillswitch=false\n")

        assertEquals(true, parsed?.tun)
        assertEquals(false, parsed?.killSwitchEnabled)
    }
}
