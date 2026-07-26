package com.verdenroz.vpnchain.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrPairingTest {

    /**
     * The pairing panel scales this matrix by a whole number of device pixels per
     * module, which only lands modules on exact pixels if it starts at one cell
     * per module. Encoding at a fixed size would silently reintroduce the
     * fractional scaling that phone cameras cannot decode.
     */
    @Test
    fun `encodes one cell per module`() {
        // 14 bytes fits QR version 1: 21 modules plus the mandatory 4-module
        // quiet zone on each side.
        val matrix = qrMatrix("VPS_IP=1.2.3.4")

        assertEquals(29, matrix.width)
        assertEquals(29, matrix.height)
    }

    @Test
    fun `grows with the payload rather than resampling to a fixed size`() {
        val small = qrMatrix("VPS_IP=1.2.3.4")
        val large = qrMatrix("VPS_IP=1.2.3.4\n" + "X".repeat(400))

        assertTrue(large.width > small.width, "expected a denser payload to add modules")
    }
}
