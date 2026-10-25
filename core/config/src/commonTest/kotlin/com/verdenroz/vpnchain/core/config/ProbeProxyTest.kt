package com.verdenroz.vpnchain.core.config

import kotlin.test.Test
import kotlin.test.assertFalse

class ProbeProxyTest {

    @Test
    fun `the probe credentials never reach a log line`() {
        val text = ProbeProxy(1, 2, 3, "probe-user", "probe-secret").toString()

        assertFalse("probe-user" in text)
        assertFalse("probe-secret" in text)
    }
}
