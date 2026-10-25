package com.verdenroz.vpnchain.core.tunnel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownRequestTest {

    private val marker = Files.createTempDirectory("down-request").resolve("down-requested").toFile()

    @Test
    fun `absent marker is not a request`() {
        assertFalse(consumeDownRequest(marker))
    }

    @Test
    fun `marker is a request once and then gone`() {
        marker.createNewFile()
        assertTrue(consumeDownRequest(marker))
        assertFalse(marker.exists())
        assertFalse(consumeDownRequest(marker))
    }
}
