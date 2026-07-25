package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClashStatsClientTest {

    @Test
    fun `pulls the two totals out of a connections payload`() {
        val body = """
            {"downloadTotal":98765,"uploadTotal":4321,"connections":[{"id":"a","upload":12}]}
        """.trimIndent()

        val totals = ClashStatsClient.parseTotals(body)

        assertEquals(4321L, totals?.uplinkBytes)
        assertEquals(98765L, totals?.downlinkBytes)
    }

    @Test
    fun `returns null when the payload carries no totals`() {
        assertNull(ClashStatsClient.parseTotals("""{"connections":[]}"""))
        assertNull(ClashStatsClient.parseTotals("not json at all"))
    }

    @Test
    fun `derives a per-second rate from two cumulative samples`() {
        assertEquals(1_000L, rateBytesPerSecond(previousBytes = 0, currentBytes = 1_000, elapsedMillis = 1_000))
        assertEquals(2_000L, rateBytesPerSecond(previousBytes = 1_000, currentBytes = 2_000, elapsedMillis = 500))
    }

    /**
     * A relay that restarted underneath us resets its counters. Reporting the
     * negative delta would render as a nonsense spike in the readout.
     */
    @Test
    fun `reports no rate when the counter went backwards`() {
        assertEquals(0L, rateBytesPerSecond(previousBytes = 5_000, currentBytes = 12, elapsedMillis = 1_000))
    }

    @Test
    fun `reports no rate for a zero or negative interval`() {
        assertEquals(0L, rateBytesPerSecond(previousBytes = 0, currentBytes = 1_000, elapsedMillis = 0))
        assertEquals(0L, rateBytesPerSecond(previousBytes = 0, currentBytes = 1_000, elapsedMillis = -5))
    }
}
