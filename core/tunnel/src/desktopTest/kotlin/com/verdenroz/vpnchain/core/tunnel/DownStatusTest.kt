package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownStatusTest {

    @Test
    fun `a chain that went down under a loaded firewall says the network is blocked`() {
        val status = downStatus(TunnelState.Error, firewallLoaded = true, carriedNothing = true)

        assertTrue(status.networkBlocked)
        assertEquals(KillSwitchState.Active, status.killSwitch)
        assertTrue(status.carriedNothing)
    }

    @Test
    fun `a chain that went down with no firewall leaves the network open`() {
        val status = downStatus(TunnelState.Disconnected, firewallLoaded = false)

        assertFalse(status.networkBlocked)
        assertEquals(KillSwitchState.Disabled, status.killSwitch)
    }

    @Test
    fun `lifting the firewall clears the block and keeps the reason the chain is down`() {
        val status = downStatus(TunnelState.Error, firewallLoaded = true, carriedNothing = true).withFirewall(false)

        assertFalse(status.networkBlocked)
        assertEquals(KillSwitchState.Disabled, status.killSwitch)
        assertEquals(TunnelState.Error, status.state)
        assertTrue(status.carriedNothing)
    }

    @Test
    fun `a connecting chain carries the block without claiming protection`() {
        val status = ChainStatus(state = TunnelState.Connecting).withFirewall(true)

        assertTrue(status.networkBlocked)
        assertEquals(KillSwitchState.Disabled, status.killSwitch)
    }

    @Test
    fun `a connected chain is left as it is`() {
        val status = ChainStatus(state = TunnelState.Connected, killSwitch = KillSwitchState.Active)

        assertEquals(status, status.withFirewall(false))
    }
}
