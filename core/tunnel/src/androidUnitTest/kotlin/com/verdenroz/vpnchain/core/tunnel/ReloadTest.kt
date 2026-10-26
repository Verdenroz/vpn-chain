package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadTest {

    @Test
    fun `a reload closes the descriptor of the TUN it replaced`() {
        assertEquals(StaleTunRelease.Close, staleTunRelease(previousFd = 41, newFd = 42))
    }

    @Test
    fun `a descriptor whose number the new TUN reuses is detached, never closed`() {
        assertEquals(StaleTunRelease.Detach, staleTunRelease(previousFd = 42, newFd = 42))
    }

    @Test
    fun `an already closed descriptor is closed again harmlessly`() {
        assertEquals(StaleTunRelease.Close, staleTunRelease(previousFd = null, newFd = 42))
    }

    @Test
    fun `a restart with no engine is a fresh start`() {
        assertFalse(engineRunning(TunnelState.Disconnected))
        assertFalse(engineRunning(TunnelState.Error))
    }

    @Test
    fun `a restart over a live or booting engine reloads it`() {
        assertTrue(engineRunning(TunnelState.Connected))
        assertTrue(engineRunning(TunnelState.Connecting))
    }
}
