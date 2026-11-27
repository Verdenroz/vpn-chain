package com.verdenroz.vpnchain.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private val EXIT = WireGuardEntry(
    privateKey = "exit-private-key",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.30",
)

private val RELAY = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
)

class DeviceExitTest {

    @Test
    fun `a relay profile keeps its device exit`() {
        val profile = SavedProfile(id = "p", name = "p", relay = RELAY, deviceExit = EXIT)

        assertEquals(EXIT, profile.effectiveFor(UserSettings()).deviceExit)
    }

    @Test
    fun `turning the entry hop off keeps the device exit`() {
        val profile = SavedProfile(id = "p", name = "p", relay = RELAY, wireGuard = EXIT, deviceExit = EXIT)

        val effective = profile.effectiveFor(UserSettings(entryHopEnabled = false))

        assertNull(effective.wireGuard)
        assertEquals(EXIT, effective.deviceExit)
    }

    @Test
    fun `a wireguard only profile drops the device exit`() {
        val profile = SavedProfile(id = "p", name = "p", wireGuard = EXIT, deviceExit = EXIT)

        assertNull(profile.effectiveFor(UserSettings()).deviceExit)
    }

    @Test
    fun `printing a profile hides the exit key`() {
        val text = SavedProfile(id = "p", name = "p", relay = RELAY, deviceExit = EXIT).toString()

        assertFalse("exit-private-key" in text)
    }
}
