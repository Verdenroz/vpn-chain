package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayConfigWireGuardOnlyTest {

    private val config = SingBoxConfigFactory.wireGuardConfig(
        WireGuardEntry(
            privateKey = "priv",
            address = "10.2.0.2/32",
            peerPublicKey = "peer",
            endpointHost = "198.51.100.7",
            dns = "10.2.0.1",
        ),
    )

    @Test
    fun `the kill switch exempts only the peer endpoint`() {
        assertEquals(listOf("198.51.100.7"), RelayConfig.exemptIps(config))
    }

    @Test
    fun `a wireguard only config reads as tun with an entry`() {
        assertTrue(RelayConfig.isTun(config))
        assertTrue(RelayConfig.hasWireGuardEntry(config))
    }
}
