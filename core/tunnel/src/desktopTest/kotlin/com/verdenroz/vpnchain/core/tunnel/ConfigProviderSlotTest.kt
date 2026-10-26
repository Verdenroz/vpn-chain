package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigProviderSlotTest {

    @Test
    fun `adoption at launch waits for a provider installed after it began`() = runTest {
        val slot = ConfigProviderSlot(waitMillis = 5_000)
        val config = async { slot.configForStaleAdopt(startConfig = null) }
        runCurrent()

        advanceTimeBy(1_000)
        slot.install { "fresh render" }
        runCurrent()

        assertEquals("fresh render", config.await())
    }

    @Test
    fun `with no provider at all adoption gives up after the wait`() = runTest {
        assertNull(ConfigProviderSlot(waitMillis = 5_000).configForStaleAdopt(startConfig = null))
    }

    @Test
    fun `a start uses the config it was handed, never a new render`() = runTest {
        val slot = ConfigProviderSlot()
        var rendered = 0
        slot.install { rendered++; "fresh render" }

        assertEquals("from start", slot.configForStaleAdopt(startConfig = "from start"))
        assertEquals(0, rendered)
    }
}
