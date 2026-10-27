package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.ProbeProxy
import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PROFILE = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
    localProxyPort = 1080,
)

private val WARP = WarpExit(
    privateKey = "warp-priv",
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "warp-peer",
)

class LibboxConnectionCloserTest {
    private val live = LiveConnections()
    private val generation = live.restart().also { gen ->
        live.opened(gen, "a", "www.bank.example", closedAt = 0)
        live.opened(gen, "b", "bank.example", closedAt = 0)
        live.opened(gen, "c", "old.reddit.com", closedAt = 0)
        live.opened(gen, "d", "reddit.com", closedAt = 0)
        live.opened(gen, "e", "notreddit.com", closedAt = 0)
        live.opened(gen, "f", "", closedAt = 0)
    }
    private val closed = mutableListOf<String>()
    private var clients = 0
    private val closer = LibboxConnectionCloser(live) {
        clients++
        { closed += it }
    }

    @Test
    fun `an exact host closes only its own connections`() = runTest {
        closer.close(listOf(HostMatch("www.bank.example", subdomains = false)))

        assertEquals(listOf("a"), closed)
    }

    @Test
    fun `a domain-level match closes the domain and the names under it`() = runTest {
        closer.close(listOf(HostMatch("reddit.com", subdomains = true)))

        assertEquals(setOf("c", "d"), closed.toSet())
    }

    @Test
    fun `a batch shares one command client`() = runTest {
        closer.close(listOf(HostMatch("bank.example", subdomains = false), HostMatch("reddit.com", subdomains = true)))

        assertEquals(setOf("b", "c", "d"), closed.toSet())
        assertEquals(1, clients)
    }

    @Test
    fun `nothing to close opens no client`() = runTest {
        closer.close(listOf(HostMatch("unknown.example", subdomains = false)))

        assertEquals(0, clients)
    }

    @Test
    fun `a connection the stream reported closed is not closed again`() = runTest {
        live.closed(generation, "a")

        closer.close(listOf(HostMatch("www.bank.example", subdomains = false)))

        assertTrue(closed.isEmpty())
    }

    @Test
    fun `an initial state entry that already closed is not tracked`() = runTest {
        live.opened(generation, "g", "news.example", closedAt = 1_700_000_000_000)

        closer.close(listOf(HostMatch("news.example", subdomains = false)))

        assertTrue(closed.isEmpty())
    }

    @Test
    fun `events from a replaced stream are dropped`() = runTest {
        val next = live.restart()
        live.opened(generation, "stale", "reddit.com", closedAt = 0)
        live.opened(next, "fresh", "reddit.com", closedAt = 0)
        live.closed(generation, "fresh")
        live.reset(generation)

        closer.close(listOf(HostMatch("reddit.com", subdomains = true)))

        assertEquals(listOf("fresh"), closed)
    }

    @Test
    fun `a refused close is thrown after every match was tried, and each is tried once`() = runTest {
        val tried = mutableListOf<String>()
        val refusing = LibboxConnectionCloser(live) { { tried += it; error("command server gone") } }

        assertFailsWith<IllegalStateException> { refusing.close(listOf(HostMatch("reddit.com", subdomains = true))) }
        refusing.close(listOf(HostMatch("reddit.com", subdomains = true)))

        assertEquals(setOf("c", "d"), tried.toSet())
        assertEquals(2, tried.size)
    }

    @Test
    fun `an Error from libbox reaches the caller as an Exception`() = runTest {
        val broken = LibboxConnectionCloser(live) { throw UnsatisfiedLinkError("no libbox") }

        val thrown = assertFailsWith<IllegalStateException> { broken.close(listOf(HostMatch("reddit.com", subdomains = true))) }

        assertTrue(thrown.cause is UnsatisfiedLinkError)
    }

    @Test
    fun `tracking is handed to the bridge`() {
        val reported = mutableListOf<Boolean>()
        LibboxConnectionCloser(live, tracking = { reported += it }).apply {
            track(true)
            track(false)
        }

        assertEquals(listOf(true, false), reported)
    }

    @Test
    fun `the connection stream runs for probes, or for routed learned sites`() {
        val learned = SingBoxConfigFactory.androidChainConfig(
            PROFILE,
            warp = WARP,
            warpMode = WarpMode.Automatic,
            learnedWarpPath = "/run/learned-warp.json",
        )
        val probed = SingBoxConfigFactory.androidChainConfig(
            PROFILE,
            warp = WARP,
            warpMode = WarpMode.Automatic,
            probe = ProbeProxy(relayPort = 41001, warpPort = 41002, fallbackPort = 0, username = "u", password = "p"),
        )

        assertTrue(wantsConnectionEvents(probed, routedSites = false))
        assertTrue(wantsConnectionEvents(learned, routedSites = true))
        assertFalse(wantsConnectionEvents(learned, routedSites = false))
    }
}
