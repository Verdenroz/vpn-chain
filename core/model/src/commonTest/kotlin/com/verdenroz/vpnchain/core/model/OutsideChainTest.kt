package com.verdenroz.vpnchain.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ENTRY = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.20",
)

private fun profile(entry: WireGuardEntry? = null) = SavedProfile(
    id = "p",
    name = "p",
    relay = ChainProfile(
        vpsIp = "203.0.113.10",
        vlessUuid = "uuid",
        realityPublicKey = "pubkey",
        shortId = "shortid",
        serverPort = 443,
    ),
    wireGuard = entry,
)

private val OPEN_SITE = LearnedSite("example.com", BlockReason.Forbidden, 0L, LearnedRoute.FailOpen)
private val DIRECT_SITE = OPEN_SITE.copy(route = LearnedRoute.Direct)

class OutsideChainTest {

    @Test
    fun `with an entry hop a failing-open site leaves by it`() {
        assertEquals(OutsideExit.EntryHop, OPEN_SITE.outsideExit(UserSettings(), profile(ENTRY)))
    }

    @Test
    fun `without an entry hop a failing-open site stays on the relay`() {
        assertNull(OPEN_SITE.outsideExit(UserSettings(), profile()))
    }

    @Test
    fun `a site the user sent direct leaves by the bare link`() {
        assertEquals(OutsideExit.Direct, DIRECT_SITE.outsideExit(UserSettings(), profile()))
    }

    @Test
    fun `a site the user sent direct still takes the entry hop when there is one`() {
        assertEquals(OutsideExit.EntryHop, DIRECT_SITE.outsideExit(UserSettings(), profile(ENTRY)))
    }

    @Test
    fun `a disabled entry hop reads as no entry hop`() {
        val settings = UserSettings(entryHopEnabled = false)

        assertNull(OPEN_SITE.outsideExit(settings, profile(ENTRY)))
        assertEquals(OutsideExit.Direct, DIRECT_SITE.outsideExit(settings, profile(ENTRY)))
    }

    @Test
    fun `desktop proxy mode never dials the entry hop`() {
        val settings = UserSettings(systemWideTun = false)

        assertNull(OPEN_SITE.outsideExit(settings, profile(ENTRY)))
        assertEquals(OutsideExit.Direct, DIRECT_SITE.outsideExit(settings, profile(ENTRY)))
    }

    @Test
    fun `nothing leaves outside the chain with failing open off`() {
        assertNull(OPEN_SITE.outsideExit(UserSettings(failOpen = false), profile(ENTRY)))
    }

    @Test
    fun `only failing-open entries leave outside the chain`() {
        listOf(
            LearnedRoute.Warp,
            LearnedRoute.RefusedByBoth,
            LearnedRoute.DirectAvailable,
            LearnedRoute.RefusedEverywhere,
        ).forEach {
            assertNull(OPEN_SITE.copy(route = it).outsideExit(UserSettings(), profile(ENTRY)), "$it")
        }
    }

    @Test
    fun `nothing fails open on a WireGuard-only connection`() {
        assertNull(OPEN_SITE.outsideExit(UserSettings(), SavedProfile(id = "w", name = "w", wireGuard = ENTRY)))
    }
}
