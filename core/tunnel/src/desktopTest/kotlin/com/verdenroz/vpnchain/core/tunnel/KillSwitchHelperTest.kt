package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.KillSwitchState
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KillSwitchHelperTest {

    private val ips = listOf("203.0.113.10", "198.51.100.20")

    private fun fault(
        exemptIps: List<String> = ips,
        allowLocal: Boolean = true,
        run: HelperRunner,
    ) = runBlocking { engageKillSwitch(exemptIps, allowLocal, run) }.fault

    @Test
    fun `exit zero engages`() {
        assertNull(fault { HelperOutcome.Exited(0, "") })
    }

    @Test
    fun `missing binary is helper not found`() {
        assertEquals(KillSwitchState.HelperNotFound, fault { HelperOutcome.NotFound })
    }

    @Test
    fun `missing capability is helper unprivileged`() {
        val output = "could not acquire CAP_NET_ADMIN - run once:\n  sudo setcap cap_net_admin=eip vpn-chain-killswitch\n"
        assertEquals(KillSwitchState.HelperUnprivileged, fault { HelperOutcome.Exited(1, output) })
    }

    @Test
    fun `nft refusal is helper unprivileged`() {
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
    fun `timeout kills the helper's children too`() {
        val pidFile = Files.createTempFile("helper-child", ".pid").toFile()
        val outcome = runBlocking {
            processHelperRunner("sh", timeoutSeconds = 1)(listOf("-c", "sleep 30 & echo \$! > ${pidFile.path}; wait"))
        }
        val child = ProcessHandle.of(pidFile.readText().trim().toLong())
        pidFile.delete()
        assertEquals(HelperOutcome.TimedOut, outcome)
        assertTrue(child.map(::awaitExit).orElse(true))
    }

    private fun awaitExit(handle: ProcessHandle): Boolean =
        runCatching { handle.onExit().get(2, TimeUnit.SECONDS) }.isSuccess

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

    private fun engagement(up: HelperOutcome, statusExit: Int, calls: MutableList<List<String>> = mutableListOf()) =
        runBlocking {
            engageKillSwitch(ips, allowLocal = true) {
                calls += it
                if (it.first() == "status") HelperOutcome.Exited(statusExit, "") else up
            }
        }

    @Test
    fun `failed up with a table present is engaged and tears nothing down`() {
        val calls = mutableListOf<List<String>>()
        val result = engagement(HelperOutcome.Exited(1, ""), statusExit = 0, calls)
        assertEquals(KillSwitchEngagement(KillSwitchState.HelperFailed, engaged = true), result)
        assertTrue(calls.none { it.first() == "down" })
    }

    @Test
    fun `failed up with no table is not engaged`() {
        val result = engagement(HelperOutcome.Exited(1, ""), statusExit = 1)
        assertEquals(KillSwitchEngagement(KillSwitchState.HelperFailed, engaged = false), result)
    }

    @Test
    fun `timed out up reports the table that is in place`() {
        assertEquals(
            KillSwitchEngagement(KillSwitchState.HelperTimedOut, engaged = true),
            engagement(HelperOutcome.TimedOut, statusExit = 0),
        )
    }

    @Test
    fun `successful up is engaged without asking status`() {
        val calls = mutableListOf<List<String>>()
        val result = engagement(HelperOutcome.Exited(0, ""), statusExit = 1, calls)
        assertEquals(KillSwitchEngagement(null, engaged = true), result)
        assertEquals(1, calls.size)
    }

    @Test
    fun `missing binary is not asked for status`() {
        val calls = mutableListOf<List<String>>()
        val result = engagement(HelperOutcome.NotFound, statusExit = 0, calls)
        assertEquals(KillSwitchEngagement(KillSwitchState.HelperNotFound, engaged = false), result)
        assertEquals(1, calls.size)
    }
}
