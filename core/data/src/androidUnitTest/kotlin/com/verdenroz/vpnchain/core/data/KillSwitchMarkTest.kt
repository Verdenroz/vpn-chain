package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class KillSwitchMarkTest {

    @Test
    fun `android never marks the direct outbound, having no nft kill switch`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.profiles.stored.value = REPOSITORY_PROFILE
        world.preferences.putLearnedSite(LearnedSite("www.shop.example", BlockReason.Forbidden, 0L, LearnedRoute.Direct))
        runCurrent()

        val config = world.controller.configProvider?.invoke().orEmpty()

        assertFalse("\"routing_mark\"" in config)
    }
}
