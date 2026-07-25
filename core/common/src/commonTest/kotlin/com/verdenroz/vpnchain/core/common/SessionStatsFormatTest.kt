package com.verdenroz.vpnchain.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStatsFormatTest {

    @Test
    fun `shows raw bytes below a kilobyte`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun `steps up through the units`() {
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.5 KB", formatBytes(1_536))
        assertEquals("1.0 MB", formatBytes(1_024L * 1_024))
        assertEquals("2.0 GB", formatBytes(2L * 1_024 * 1_024 * 1_024))
    }

    /** A long session must not run off the end of the unit list. */
    @Test
    fun `clamps at the largest unit it knows`() {
        assertEquals("1.0 TB", formatBytes(1_024L * 1_024 * 1_024 * 1_024))
        assertEquals("2048.0 TB", formatBytes(2_048L * 1_024 * 1_024 * 1_024 * 1_024))
    }

    @Test
    fun `rates carry a per-second suffix`() {
        assertEquals("1.0 MB/s", formatRate(1_024L * 1_024))
        assertEquals("0 B/s", formatRate(0))
    }

    @Test
    fun `duration drops the hour field below an hour`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_000))
        assertEquals("02:03", formatDuration(123_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `duration grows an hour field past an hour`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("25:00:01", formatDuration(90_001_000))
    }

    /** Clock skew between samples must not render a negative uptime. */
    @Test
    fun `treats a negative elapsed time as zero`() {
        assertEquals("00:00", formatDuration(-5_000))
    }
}
