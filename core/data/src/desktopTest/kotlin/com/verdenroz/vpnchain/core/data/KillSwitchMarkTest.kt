package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KillSwitchMarkTest {

    @Test
    fun `a chain with no entry hop marks its direct outbound for a site sent direct`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.profiles.stored.value = REPOSITORY_PROFILE
        world.preferences.putLearnedSite(LearnedSite("www.shop.example", BlockReason.Forbidden, 0L, LearnedRoute.Direct))
        runCurrent()

        val config = world.controller.configProvider?.invoke().orEmpty()

        assertTrue("\"routing_mark\"" in config)
        assertTrue("www.shop.example" !in config)
    }

    @Test
    fun `without the bare link's rule nothing is marked`() = runTest {
        val world = RepositoryWorld(backgroundScope, settings = UserSettings(failOpen = false))
        world.profiles.stored.value = REPOSITORY_PROFILE
        world.preferences.putLearnedSite(LearnedSite("www.shop.example", BlockReason.Forbidden, 0L, LearnedRoute.Direct))
        runCurrent()

        val config = world.controller.configProvider?.invoke().orEmpty()

        assertFalse("\"routing_mark\"" in config)
    }
}
