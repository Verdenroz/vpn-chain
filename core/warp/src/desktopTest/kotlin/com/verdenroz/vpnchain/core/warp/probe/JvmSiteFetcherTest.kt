package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.config.ProbeProxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmSiteFetcherTest {

    @Test
    fun `cancelling a fetch ends a read that would otherwise drip on`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        repeat(60) {
                            client.getOutputStream().write('H'.code)
                            client.getOutputStream().flush()
                            Thread.sleep(1_000)
                        }
                    }
                }
            }
            val proxy = ProbeProxy(server.localPort, 0, 0, "u", "p")
            val started = System.nanoTime()

            val response = withTimeoutOrNull(500) { JvmSiteFetcher().get("example.com", server.localPort, proxy) }

            assertNull(response)
            assertTrue(System.nanoTime() - started < 3_000_000_000L)
        }
    }
}
