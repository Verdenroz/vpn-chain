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
                    "10.19.19.1/30"
                ],
                "mtu": 1280
            }
        ],
        "endpoints": [
            {
                "type": "wireguard",
                "tag": "proton-entry",
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

    @Test
    fun `reads the listen port and falls back when there is no listener`() {
        assertEquals(1081, RelayConfig.proxyPort(PROXY_ONLY, default = 1080))
        // A TUN config has no inbound listener at all — the default must survive.
        assertEquals(1080, RelayConfig.proxyPort(TUN_WITH_ENTRY, default = 1080))
    }

    /**
     * The kill switch blocks everything except these. Missing the Proton peer
     * here is what leaves sing-box unable to reach its own entry hop, so the
     * machine ends up fail-closed against a tunnel that can never come up.
     */
    @Test
    fun `exempts both the vps and the proton peer endpoint`() {
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
