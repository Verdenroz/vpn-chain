package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LinkRecheckTest {

    private val wifi = setOf("wlan0/192.168.1.5")
    private val poll = 4_000L

    @Test
    fun `the same link one poll later has not moved`() {
        assertFalse(linkMoved(previous = wifi, current = wifi, elapsedMs = poll, pollMs = poll))
    }

    @Test
    fun `a different address or interface is a move`() {
        assertTrue(linkMoved(wifi, setOf("wlan0/10.0.0.7"), elapsedMs = poll, pollMs = poll))
        assertTrue(linkMoved(wifi, wifi + "enp1s0/192.168.1.9", elapsedMs = poll, pollMs = poll))
    }

    @Test
    fun `a link that came back is a move`() {
        assertTrue(linkMoved(previous = emptySet(), current = wifi, elapsedMs = poll, pollMs = poll))
    }

    @Test
    fun `a link that went away is not, since there is nothing to check on`() {
        assertFalse(linkMoved(previous = wifi, current = emptySet(), elapsedMs = poll, pollMs = poll))
    }

    @Test
    fun `a poll that overran by a sleep is a move on the same link`() {
        assertTrue(linkMoved(wifi, wifi, elapsedMs = poll + WAKE_GAP_MS + 1, pollMs = poll))
        assertFalse(linkMoved(wifi, wifi, elapsedMs = poll + WAKE_GAP_MS, pollMs = poll))
    }

    @Test
    fun `with no link change the next check waits out the interval`() = runTest {
        awaitNextCheck(Channel(Channel.CONFLATED), intervalMs = 45_000, settleMs = 5_000)

        assertEquals(45_000L, currentTime)
    }

    @Test
    fun `a link change brings the next check forward to one settle away`() = runTest {
        val recheck = Channel<Unit>(Channel.CONFLATED)
        recheck.send(Unit)

        awaitNextCheck(recheck, intervalMs = 45_000, settleMs = 5_000)

        assertEquals(5_000L, currentTime)
    }
}
