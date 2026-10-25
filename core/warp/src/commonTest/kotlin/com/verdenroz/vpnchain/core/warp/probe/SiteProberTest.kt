package com.verdenroz.vpnchain.core.warp.probe

import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.model.BlockReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

private val PROXY = ProbeProxy(relayPort = 1, warpPort = 2, username = "u", password = "p")

private val OPEN = ProbeResponse(200, emptyMap(), "ok", complete = true)
private val REFUSED = ProbeResponse(403, emptyMap(), "", complete = true)

private class FakeFetcher(private val byPort: Map<Int, ProbeResponse?>) : SiteFetcher {
    val ports = mutableListOf<Int>()

    override suspend fun get(host: String, port: Int, proxy: ProbeProxy): ProbeResponse? {
        ports += port
        return byPort[port]
    }
}

class SiteProberTest {

    @Test
    fun `a site the relay reaches is never probed through warp`() = runTest {
        val fetcher = FakeFetcher(mapOf(1 to OPEN, 2 to REFUSED))

        assertEquals(ProbeOutcome.Reachable, SiteProber(fetcher).probe("example.com", PROXY))
        assertEquals(listOf(1), fetcher.ports)
    }

    @Test
    fun `refused by the relay and answered by warp is warp-only`() = runTest {
        val fetcher = FakeFetcher(mapOf(1 to REFUSED, 2 to OPEN))

        assertEquals(
            ProbeOutcome.WarpOnly(BlockReason.Forbidden),
            SiteProber(fetcher).probe("example.com", PROXY),
        )
    }

    @Test
    fun `refused by both keeps the relay's reason`() = runTest {
        val fetcher = FakeFetcher(
            mapOf(1 to ProbeResponse(429, emptyMap(), "", true), 2 to REFUSED),
        )

        assertEquals(
            ProbeOutcome.RefusedByBoth(BlockReason.RateLimited),
            SiteProber(fetcher).probe("example.com", PROXY),
        )
    }

    @Test
    fun `a failed relay probe is unknown and does not reach warp`() = runTest {
        val fetcher = FakeFetcher(mapOf(1 to null, 2 to OPEN))

        assertEquals(ProbeOutcome.Unknown, SiteProber(fetcher).probe("example.com", PROXY))
        assertEquals(listOf(1), fetcher.ports)
    }

    @Test
    fun `a failed warp probe is unknown rather than refused by both`() = runTest {
        val fetcher = FakeFetcher(mapOf(1 to REFUSED, 2 to null))

        assertEquals(ProbeOutcome.Unknown, SiteProber(fetcher).probe("example.com", PROXY))
    }

    @Test
    fun `no warp probe means a refusal cannot be acted on`() = runTest {
        val fetcher = FakeFetcher(mapOf(1 to REFUSED))

        assertEquals(
            ProbeOutcome.Unknown,
            SiteProber(fetcher).probe("example.com", PROXY.copy(warpPort = 0)),
        )
        assertEquals(listOf(1), fetcher.ports)
    }
}
