package com.verdenroz.vpnchain.desktop.cli

import com.verdenroz.vpnchain.core.engine.EngineStatus
import com.verdenroz.vpnchain.core.engine.LogLine
import com.verdenroz.vpnchain.core.engine.ProfileSummary
import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals

class CliOutputTest {
    private val home = ProfileSummary("p1", "Home", ConnectionKind.Chain, active = true)
    private val phone = ProfileSummary("p2", "Phone hotspot", ConnectionKind.WireGuard, active = false)
    private val connected = EngineStatus(
        state = TunnelState.Connected,
        profile = home,
        entryHopActive = true,
        killSwitch = KillSwitchState.Active,
        exitIp = "198.51.100.7",
    )

    @Test
    fun `status as text`() {
        assertEquals(
            """
            state       connected
            connection  Home (chain)
            entry hop   up
            kill switch active
            exit ip     198.51.100.7
            detail      system-wide (TUN)
            """.trimIndent(),
            statusText(connected, "system-wide (TUN)"),
        )
    }

    @Test
    fun `status with nothing configured`() {
        assertEquals(
            """
            state       disconnected
            connection  none
            entry hop   down
            kill switch helper-unprivileged
            """.trimIndent(),
            statusText(EngineStatus(TunnelState.Disconnected, killSwitch = KillSwitchState.HelperUnprivileged), null),
        )
    }

    @Test
    fun `status as json`() {
        assertEquals(
            """{"state":"connected","connection":{"id":"p1","name":"Home","kind":"chain","active":true},""" +
                """"entryHop":true,"killSwitch":"active","exitIp":"198.51.100.7","detail":null,"willRetry":false}""",
            statusJson(connected, null).toString(),
        )
    }

    @Test
    fun `profiles as text`() {
        assertEquals(
            """
            * Home           chain      p1
              Phone hotspot  wireguard  p2
            """.trimIndent(),
            profilesText(listOf(home, phone)),
        )
    }

    @Test
    fun `no profiles`() {
        assertEquals("no saved connections", profilesText(emptyList()))
    }

    @Test
    fun `profiles as json`() {
        assertEquals(
            """[{"id":"p1","name":"Home","kind":"chain","active":true},""" +
                """{"id":"p2","name":"Phone hotspot","kind":"wireguard","active":false}]""",
            profilesJson(listOf(home, phone)).toString(),
        )
    }

    @Test
    fun `logs as text and json`() {
        val lines = listOf(LogLine(1, "relay started"), LogLine(2, "kill switch engaged"))
        assertEquals("relay started\nkill switch engaged", logsText(lines))
        assertEquals(
            """[{"timestampMillis":1,"message":"relay started"},{"timestampMillis":2,"message":"kill switch engaged"}]""",
            logsJson(lines).toString(),
        )
    }
}
