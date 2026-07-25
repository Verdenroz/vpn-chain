package com.verdenroz.vpnchain.feature.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogLineFormatTest {

    @Test
    fun `splits a sing-box line into level and message`() {
        val parsed = parseLogLine(
            "-0400 2026-07-24 18:09:14 INFO [1996250765 0ms] inbound/tun[tun-in]: inbound packet",
        )

        assertEquals("INFO", parsed.level)
        assertEquals("[1996250765 0ms] inbound/tun[tun-in]: inbound packet", parsed.body)
    }

    @Test
    fun `strips a positive timezone offset too`() {
        val parsed = parseLogLine("+0200 2026-07-24 18:09:14 WARN connection reset")

        assertEquals("WARN", parsed.level)
        assertEquals("connection reset", parsed.body)
    }

    @Test
    fun `recognises every level sing-box emits`() {
        listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "PANIC").forEach { level ->
            val parsed = parseLogLine("-0400 2026-07-24 18:09:14 $level something happened")
            assertEquals(level, parsed.level, "failed for $level")
            assertEquals("something happened", parsed.body)
        }
    }

    /** Controller messages are ours, not sing-box's, and must survive untouched. */
    @Test
    fun `leaves the app's own lines alone`() {
        val raw = "kill switch engaged (nftables, exempting 89.127.235.38)"

        val parsed = parseLogLine(raw)

        assertNull(parsed.level)
        assertEquals(raw, parsed.body)
    }

    @Test
    fun `keeps a line that only looks like it has a stamp`() {
        val raw = "adopted running system-wide relay (started outside this app)"

        assertEquals(raw, parseLogLine(raw).body)
    }

    @Test
    fun `does not treat a level word inside the message as the level`() {
        val parsed = parseLogLine("the tunnel reported ERROR downstream")

        assertNull(parsed.level)
    }

    @Test
    fun `handles a stamped line with no level`() {
        val parsed = parseLogLine("-0400 2026-07-24 18:09:14 bare message")

        assertNull(parsed.level)
        assertEquals("bare message", parsed.body)
    }
}
