package com.verdenroz.vpnchain.desktop.cli

import com.verdenroz.vpnchain.core.engine.EngineStatus
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AwaitSettledTest {
    private fun status(state: TunnelState, willRetry: Boolean = false) =
        EngineStatus(state, killSwitch = KillSwitchState.Disabled, willRetry = willRetry)

    private fun feed(vararg statuses: EngineStatus): Pair<EngineStatus?, Int> {
        var seen = 0
        val settled = awaitSettled { onStatus ->
            for (status in statuses) {
                seen++
                if (!onStatus(status)) break
            }
        }
        return settled to seen
    }

    @Test
    fun `an error the engine will redial keeps waiting for the reconnect`() {
        val (settled, seen) = feed(
            status(TunnelState.Connecting),
            status(TunnelState.Error, willRetry = true),
            status(TunnelState.Connected),
        )
        assertEquals(TunnelState.Connected, settled?.state)
        assertEquals(3, seen)
    }

    @Test
    fun `an error nothing will redial settles at once`() {
        val (settled, seen) = feed(status(TunnelState.Error), status(TunnelState.Connected))
        assertEquals(TunnelState.Error, settled?.state)
        assertEquals(1, seen)
    }

    @Test
    fun `a stop settles`() {
        assertEquals(TunnelState.Disconnected, feed(status(TunnelState.Connecting), status(TunnelState.Disconnected)).first?.state)
    }

    @Test
    fun `a feed that ends unsettled gives nothing`() {
        assertNull(feed(status(TunnelState.Error, willRetry = true)).first)
    }
}
