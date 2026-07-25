package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayConfigClashApiTest {

    @Test
    fun `reads back the controller port and secret it was rendered with`() {
        val config = """
            {"experimental":{"clash_api":{"external_controller":"127.0.0.1:41739","secret":"a1b2c3"}}}
        """.trimIndent()

        val api = RelayConfig.clashApi(config)

        assertNotNull(api)
        assertEquals(41739, api.port)
        assertEquals("a1b2c3", api.secret)
    }

    /** A relay adopted from the CLI renders no clash API; that is not an error. */
    @Test
    fun `returns null when the config exposes no clash api`() {
        assertNull(RelayConfig.clashApi("""{"log":{"level":"info"}}"""))
    }

    /**
     * Regression guard, in the shape of the NetShield DNS bug: the kill switch
     * picks exempt IPs out of this same JSON by regex, and stranding the machine
     * behind a firewall that blocks the actual relay is the worst outcome here.
     */
    @Test
    fun `adding a clash api does not disturb the kill switch exemptions`() {
        val withoutApi = TUN_WITH_ENTRY
        val withApi = TUN_WITH_ENTRY.replaceFirst(
            "{",
            """{"experimental":{"clash_api":{"external_controller":"127.0.0.1:41739","secret":"deadbeef"}},""",
        )

        val before = RelayConfig.exemptIps(withoutApi)
        val after = RelayConfig.exemptIps(withApi)

        assertEquals(before, after)
        assertTrue("127.0.0.1" !in after, "loopback must never displace a real exemption")
    }
}

private val TUN_WITH_ENTRY = """
    {"inbounds":[{"type":"tun","tag":"tun-in"}],
     "endpoints":[{"type":"wireguard","peers":[{"address":"146.70.198.34","port":51820}]}],
     "outbounds":[{"type":"vless","server":"89.127.235.38","server_port":443}]}
""".trimIndent()
