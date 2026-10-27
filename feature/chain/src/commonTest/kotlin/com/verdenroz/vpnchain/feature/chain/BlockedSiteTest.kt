package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val RELAY = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "ab12",
)

private val ENTRY = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "198.51.100.7",
)

private val SITES = listOf(
    LearnedSite("reddit.com", BlockReason.Forbidden, 5L, LearnedRoute.Warp),
    LearnedSite("www.shop.example", BlockReason.Forbidden, 2L, LearnedRoute.DirectAvailable),
    LearnedSite("old.example", BlockReason.Forbidden, 1L, LearnedRoute.DirectAvailable),
    LearnedSite("mail.example", BlockReason.Forbidden, 9L, LearnedRoute.Direct),
)

private val RELAY_ONLY = SavedProfile("r", "r", relay = RELAY)
private val CHAIN = SavedProfile("c", "c", relay = RELAY, wireGuard = ENTRY)

class BlockedSiteTest {

    @Test
    fun `the newest offered site is prompted, shown without www`() {
        assertEquals(
            BlockedSite("www.shop.example", "shop.example"),
            blockedSite(SITES, UserSettings(), RELAY_ONLY, dismissed = emptySet()),
        )
    }

    @Test
    fun `a dismissed site gives way to the next newest`() {
        assertEquals(
            BlockedSite("old.example", "old.example"),
            blockedSite(SITES, UserSettings(), RELAY_ONLY, dismissed = setOf("www.shop.example")),
        )
    }

    @Test
    fun `nothing is prompted once every offered site is dismissed`() {
        assertNull(blockedSite(SITES, UserSettings(), RELAY_ONLY, dismissed = setOf("www.shop.example", "old.example")))
    }

    @Test
    fun `a chain with its entry hop dialled prompts for the site waiting on a tap`() {
        assertEquals("www.shop.example", blockedSite(SITES, UserSettings(), CHAIN, dismissed = emptySet())?.domain)
    }

    @Test
    fun `failing open off prompts nothing`() {
        assertNull(blockedSite(SITES, UserSettings(failOpen = false), RELAY_ONLY, dismissed = emptySet()))
    }

    @Test
    fun `a WireGuard-only connection prompts nothing`() {
        assertNull(blockedSite(SITES, UserSettings(), SavedProfile("w", "w", wireGuard = ENTRY), dismissed = emptySet()))
    }
}
