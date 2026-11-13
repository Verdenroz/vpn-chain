package com.verdenroz.vpnchain.core.tunnel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RelayConfigCacheTest {

    private fun config(cachePath: String) =
        """{"experimental":{"clash_api":{"secret":"a1b2c3"},"cache_file":{"enabled":true,"path":"$cachePath"}},"route":{}}"""

    @Test
    fun `a cache this user owns is deleted and the config kept`() {
        val cache = Files.createTempFile("singbox-cache", ".db").toFile()
        val config = config(cache.absolutePath)

        assertEquals(config, RelayConfig.withoutUnreadableCache(config, fallbackPath = "/var/lib/vpn-chain/singbox-cache-1.db"))
        assertFalse(cache.exists())
    }

    @Test
    fun `a cache that cannot be deleted is superseded, leaving the rest of the config alone`() {
        val config = config("/nonexistent/singbox-cache.db")

        assertEquals(
            config("/var/lib/vpn-chain/singbox-cache-1.db"),
            RelayConfig.withoutUnreadableCache(config, fallbackPath = "/var/lib/vpn-chain/singbox-cache-1.db"),
        )
    }

    @Test
    fun `a config with no cache has nothing to retry with`() {
        assertNull(RelayConfig.withoutUnreadableCache("""{"route":{}}""", fallbackPath = "/var/lib/vpn-chain/singbox-cache-1.db"))
    }
}
