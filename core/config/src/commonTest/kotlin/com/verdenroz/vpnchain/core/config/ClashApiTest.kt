package com.verdenroz.vpnchain.core.config

import kotlin.test.Test
import kotlin.test.assertFalse

class ClashApiTest {

    @Test
    fun `the secret never reaches a log line`() {
        assertFalse("clash-secret" in ClashApi(9090, "clash-secret").toString())
    }
}
