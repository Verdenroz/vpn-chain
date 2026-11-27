package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayConfigDeviceExitTest {

    private val relay = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "u", realityPublicKey = "k", shortId = "s")
    private val entry = WireGuardEntry("priv", "10.2.0.2/32", "peer", "198.51.100.20")
    private val exit = WireGuardEntry("exit-priv", "10.5.0.2/32", "exit-peer", "198.51.100.30")
    private val warp = WarpExit("k", "172.16.0.2/32", "2606:4700::2/128", "p")

    @Test
    fun `the kill switch never exempts the device exit`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            profile = relay,
            entry = entry,
            warp = warp,
            warpMode = WarpMode.Automatic,
            deviceExit = exit,
        )

        assertTrue("198.51.100.30" in config)
        assertEquals(listOf("203.0.113.10", "198.51.100.20"), RelayConfig.exemptIps(config))
    }

    @Test
    fun `an exit alone is not an entry hop`() {
        val config = SingBoxConfigFactory.androidChainConfig(profile = relay, deviceExit = exit)

        assertFalse(RelayConfig.hasWireGuardEntry(config))
        assertEquals(listOf("203.0.113.10"), RelayConfig.exemptIps(config))
    }
}
