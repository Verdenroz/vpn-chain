package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlin.test.Test
import kotlin.test.assertEquals

private val PROFILE = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    serverPort = 443,
)

class RelayConfigMarkTest {

    @Test
    fun `a marked direct outbound becomes the helper's mark argument`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            PROFILE,
            warpMode = WarpMode.Automatic,
            learnedDirectPath = "/run/learned-direct.json",
            failOpen = true,
            markDirect = true,
        )

        assertEquals(listOf("--allow-mark", "0x760000"), RelayConfig.markArgs(config))
    }

    @Test
    fun `no mark means no argument`() {
        assertEquals(emptyList(), RelayConfig.markArgs(SingBoxConfigFactory.androidChainConfig(PROFILE)))
    }

    @Test
    fun `a config that is not json means no argument`() {
        assertEquals(emptyList(), RelayConfig.markArgs("{ not json"))
    }
}
