package com.verdenroz.vpnchain.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemdRelayCacheTest {

    @Test
    fun `an empty state directory uses the plain cache name`() {
        assertEquals("singbox-cache.db", SystemdRelay.cacheFileName(emptyList()))
    }

    @Test
    fun `the newest cache wins over the ones it superseded`() {
        val existing = listOf("singbox-cache.db", "singbox-cache-2.db", "singbox-cache-10.db", "relay.log")

        assertEquals("singbox-cache-10.db", SystemdRelay.cacheFileName(existing))
    }

    @Test
    fun `the next cache is one past the newest`() {
        assertEquals("singbox-cache-1.db", SystemdRelay.cacheFileName(listOf("singbox-cache.db"), step = 1))
        assertEquals("singbox-cache-3.db", SystemdRelay.cacheFileName(listOf("singbox-cache-2.db"), step = 1))
    }
}
