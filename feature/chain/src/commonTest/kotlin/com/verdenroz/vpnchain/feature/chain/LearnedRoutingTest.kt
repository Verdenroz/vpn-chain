package com.verdenroz.vpnchain.feature.chain

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.OutsideExit
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
    LearnedSite("stuck.example", BlockReason.Forbidden, 0L, LearnedRoute.RefusedByBoth),
)

class LearnedRoutingTest {

    @Test
    fun `counts the sites WARP carries and the ones leaving by the bare link`() {
        val routing = learnedRouting(SITES, UserSettings(), SavedProfile("r", "r", relay = RELAY))

        assertEquals(LearnedRouting(WarpMode.Automatic, warpCount = 2, outsideCount = 1, outsideExit = OutsideExit.Direct), routing)
    }

    @Test
    fun `a chain fails open through its entry hop`() {
        val routing = learnedRouting(SITES, UserSettings(), SavedProfile("c", "c", relay = RELAY, wireGuard = ENTRY))

        assertEquals(OutsideExit.EntryHop, routing.outsideExit)
    }

    @Test
    fun `nothing leaves outside the chain with failing open off`() {
        val routing = learnedRouting(SITES, UserSettings(failOpen = false), SavedProfile("r", "r", relay = RELAY))

        assertEquals(0, routing.outsideCount)
        assertEquals(null, routing.outsideExit)
    }

    @Test
    fun `a WireGuard-only connection reroutes nothing`() {
        assertEquals(LearnedRouting(), learnedRouting(SITES, UserSettings(), SavedProfile("w", "w", wireGuard = ENTRY)))
    }
}
