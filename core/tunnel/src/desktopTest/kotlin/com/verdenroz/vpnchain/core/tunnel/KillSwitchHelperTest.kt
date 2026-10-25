package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.KillSwitchState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KillSwitchHelperTest {

    private val ips = listOf("203.0.113.10", "198.51.100.20")

    private fun fault(
        exemptIps: List<String> = ips,
        allowLocal: Boolean = true,
        run: HelperRunner,
    ) = runBlocking { engageKillSwitchFault(exemptIps, allowLocal, run) }

    @Test
    fun `exit zero engages`() {
        assertNull(fault { HelperOutcome.Exited(0, "") })
    }

    @Test
    fun `missing binary is helper not found`() {
        assertEquals(KillSwitchState.HelperNotFound, fault { HelperOutcome.NotFound })
    }

    @Test
    fun `non-zero exit is helper unprivileged`() {
        assertEquals(
            KillSwitchState.HelperUnprivileged,
            fault { HelperOutcome.Exited(1, "Operation not permitted") },
        )
    }

    @Test
    fun `other non-zero exit is helper failed`() {
        assertEquals(KillSwitchState.HelperFailed, fault { HelperOutcome.Exited(2, "usage: vpn-chain-killswitch") })
    }

    @Test
    fun `permission denied output is helper unprivileged`() {
        assertEquals(KillSwitchState.HelperUnprivileged, fault { HelperOutcome.Exited(1, "Permission denied") })
    }

    @Test
    fun `hung process times out instead of blocking`() {
        val outcome = runBlocking { processHelperRunner("sleep", timeoutSeconds = 1)(listOf("10")) }
        assertEquals(HelperOutcome.TimedOut, outcome)
    }

    @Test
    fun `absent binary is not found`() {
        val outcome = runBlocking { processHelperRunner("vpn-chain-no-such-bin")(emptyList()) }
        assertEquals(HelperOutcome.NotFound, outcome)
    }

    @Test
    fun `timeout is helper timed out`() {
        assertEquals(KillSwitchState.HelperTimedOut, fault { HelperOutcome.TimedOut })
    }

    @Test
    fun `empty exemptions never reach the helper`() {
        var called = false
        val result = fault(exemptIps = emptyList()) {
            called = true
            HelperOutcome.Exited(0, "")
        }
        assertEquals(KillSwitchState.NoExemptAddresses, result)
        assertFalse(called)
    }

    @Test
    fun `up args carry the local flag and exemptions`() {
        var args = emptyList<String>()
        fault(allowLocal = true) { args = it; HelperOutcome.Exited(0, "") }
        assertEquals(listOf("up", "--allow-local") + ips, args)
        fault(allowLocal = false) { args = it; HelperOutcome.Exited(0, "") }
        assertEquals(listOf("up") + ips, args)
    }
}
