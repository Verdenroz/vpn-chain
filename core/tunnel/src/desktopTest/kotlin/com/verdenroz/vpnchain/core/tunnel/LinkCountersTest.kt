package com.verdenroz.vpnchain.core.tunnel

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkCountersTest {

    private val sysNet: File = Files.createTempDirectory("sys-net").toFile().also { it.deleteOnExit() }

    private fun iface(name: String, rx: Long, tx: Long, physical: Boolean) {
        val dir = File(sysNet, name)
        File(dir, "statistics").mkdirs()
        File(dir, "statistics/rx_packets").writeText("$rx\n")
        File(dir, "statistics/tx_packets").writeText("$tx\n")
        if (physical) File(dir, "device").mkdirs()
    }

    @Test
    fun `sums the physical interfaces and skips virtual ones`() {
        iface("wlan0", rx = 100, tx = 40, physical = true)
        iface("enp1s0", rx = 7, tx = 3, physical = true)
        iface("tun0", rx = 9_000, tx = 9_000, physical = false)
        iface("lo", rx = 500, tx = 500, physical = false)

        assertEquals(LinkCounters(rx = 107, tx = 43), readLinkCounters(sysNet))
    }

    @Test
    fun `reads nothing where there is no physical interface to judge`() {
        iface("tun0", rx = 1, tx = 1, physical = false)

        assertNull(readLinkCounters(sysNet))
        assertNull(readLinkCounters(File(sysNet, "absent")))
    }

    @Test
    fun `a link that took packets and returned none is the cause`() {
        assertEquals(StallCause.Link, stallCause(LinkCounters(rx = 100, tx = 40), LinkCounters(rx = 100, tx = 52)))
    }

    @Test
    fun `a link still delivering packets blames the chain`() {
        assertEquals(StallCause.Chain, stallCause(LinkCounters(rx = 100, tx = 40), LinkCounters(rx = 101, tx = 52)))
    }

    @Test
    fun `a link nothing was sent on proves nothing`() {
        assertEquals(StallCause.Chain, stallCause(LinkCounters(rx = 100, tx = 40), LinkCounters(rx = 100, tx = 40)))
    }

    @Test
    fun `unreadable counters blame the chain`() {
        assertEquals(StallCause.Chain, stallCause(null, LinkCounters(rx = 100, tx = 52)))
        assertEquals(StallCause.Chain, stallCause(LinkCounters(rx = 100, tx = 40), null))
    }

    @Test
    fun `a chain at fault is rebuilt at once`() {
        assertTrue(shouldRebuild(StallCause.Chain, heldForMs = null))
    }

    @Test
    fun `a silent link holds the chain until the limit, then rebuilds it`() {
        assertFalse(shouldRebuild(StallCause.Link, heldForMs = null))
        assertFalse(shouldRebuild(StallCause.Link, heldForMs = LINK_HOLD_LIMIT_MS - 1))
        assertTrue(shouldRebuild(StallCause.Link, heldForMs = LINK_HOLD_LIMIT_MS))
    }
}
