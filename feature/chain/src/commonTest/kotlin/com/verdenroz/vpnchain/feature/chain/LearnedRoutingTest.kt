package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals

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
    LearnedSite("reddit.com", BlockReason.Forbidden, 0L, LearnedRoute.Warp),
    LearnedSite("chatgpt.com", BlockReason.Forbidden, 0L, LearnedRoute.Warp),
    LearnedSite("bank.example", BlockReason.Forbidden, 0L, LearnedRoute.FailOpen),
    LearnedSite("shop.example", BlockReason.Forbidden, 0L, LearnedRoute.DirectAvailable),
    LearnedSite("mail.example", BlockReason.Forbidden, 0L, LearnedRoute.Direct),
    LearnedSite("stuck.example", BlockReason.Forbidden, 0L, LearnedRoute.RefusedByBoth),
)

private val RELAY_ONLY = SavedProfile("r", "r", relay = RELAY)
private val CHAIN = SavedProfile("c", "c", relay = RELAY, wireGuard = ENTRY)

class LearnedRoutingTest {

    @Test
    fun `without an entry hop refused sites are offered and sent ones listed`() {
        val routing = learnedRouting(SITES, UserSettings(), RELAY_ONLY, warpRegistered = true)

        assertEquals(
            LearnedRouting(
                WarpMode.Automatic,
                warpCount = 2,
                entryHopCount = 0,
                offered = listOf("shop.example"),
                sentDirect = listOf("mail.example"),
            ),
            routing,
        )
    }

    @Test
    fun `a chain fails open through its entry hop and offers nothing direct`() {
        val routing = learnedRouting(SITES, UserSettings(), CHAIN, warpRegistered = true)

        assertEquals(2, routing.entryHopCount)
        assertEquals(emptyList(), routing.offered)
        assertEquals(emptyList(), routing.sentDirect)
    }

    @Test
    fun `nothing leaves outside the chain with failing open off`() {
        val routing = learnedRouting(SITES, UserSettings(failOpen = false), RELAY_ONLY, warpRegistered = true)

        assertEquals(0, routing.entryHopCount)
        assertEquals(emptyList(), routing.offered)
        assertEquals(emptyList(), routing.sentDirect)
    }

    @Test
    fun `no registration means nothing is rerouted`() {
        val routing = learnedRouting(SITES, UserSettings(), RELAY_ONLY, warpRegistered = false)

        assertEquals(WarpMode.Off, routing.warpMode)
        assertEquals(0, routing.warpCount)
    }

    @Test
    fun `a WireGuard-only connection reroutes nothing`() {
        assertEquals(
            LearnedRouting(),
            learnedRouting(SITES, UserSettings(), SavedProfile("w", "w", wireGuard = ENTRY), warpRegistered = true),
        )
    }
}
