package com.verdenroz.vpnchain.core.model

import kotlin.test.Test
import kotlin.test.assertFalse

class WarpExitTest {

    @Test
    fun `the private key never reaches a log line`() {
        val exit = WarpExit("secret-private-key", "172.16.0.2/32", "2606:4700::2/128", "peer")

        assertFalse("secret-private-key" in exit.toString())
    }
}
