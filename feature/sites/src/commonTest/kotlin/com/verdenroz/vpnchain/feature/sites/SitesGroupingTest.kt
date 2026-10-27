package com.verdenroz.vpnchain.feature.sites

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val RELAY = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")

private val ENTRY = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.7",
)

private val RELAY_ONLY = SavedProfile("r", "r", relay = RELAY)
private val CHAIN = SavedProfile("c", "c", relay = RELAY, wireGuard = ENTRY)

private fun site(domain: String, route: LearnedRoute, at: Long = 0L) = LearnedSite(domain, BlockReason.Forbidden, at, route)

class SitesGroupingTest {

    @Test
    fun `without an entry hop each site lands in its section with its action`() {
        val state = sitesUiState(
            listOf(
                site("www.reddit.com", LearnedRoute.Warp),
                site("shop.example", LearnedRoute.DirectAvailable),
                site("mail.example", LearnedRoute.Direct),
                site("stuck.example", LearnedRoute.RefusedEverywhere),
            ),
            UserSettings(),
            RELAY_ONLY,
        )

        assertEquals(
            SitesUiState(
                blocked = listOf(
                    SiteRow("shop.example", "shop.example", SiteAction.UseMyConnection),
                    SiteRow("stuck.example", "stuck.example", SiteAction.Remove),
                ),
                direct = listOf(SiteRow("mail.example", "mail.example", SiteAction.Undo)),
                warp = listOf(SiteRow("www.reddit.com", "reddit.com", SiteAction.Remove)),
            ),
            state,
        )
    }

    @Test
    fun `a chain lists its fail-open sites under the entry and offers nothing direct`() {
        val state = sitesUiState(
            listOf(site("bank.example", LearnedRoute.FailOpen), site("shop.example", LearnedRoute.DirectAvailable)),
            UserSettings(),
            CHAIN,
        )

        assertEquals(listOf(SiteRow("bank.example", "bank.example", SiteAction.Remove)), state.entry)
        assertEquals(listOf(SiteRow("shop.example", "shop.example", SiteAction.Remove)), state.blocked)
    }

    @Test
    fun `each section lists the newest first`() {
        val state = sitesUiState(
            listOf(site("old.example", LearnedRoute.Warp, at = 1L), site("new.example", LearnedRoute.Warp, at = 2L)),
            UserSettings(),
            RELAY_ONLY,
        )

        assertEquals(listOf("new.example", "old.example"), state.warp.map { it.domain })
    }

    @Test
    fun `www stays when the bare host is listed too`() {
        val state = sitesUiState(
            listOf(site("www.reddit.com", LearnedRoute.Warp), site("reddit.com", LearnedRoute.Warp)),
            UserSettings(),
            RELAY_ONLY,
        )

        assertEquals(setOf("www.reddit.com", "reddit.com"), state.warp.map { it.host }.toSet())
    }

    @Test
    fun `no learned sites reads as empty`() {
        assertTrue(sitesUiState(emptyList(), UserSettings(), RELAY_ONLY).isEmpty)
    }
}
