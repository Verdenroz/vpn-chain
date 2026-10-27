package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AwaitConnectedTest {

    private val status = MutableStateFlow(ChainStatus(state = TunnelState.Connecting))

    @Test
    fun `returns once the tunnel is connected`() = runTest {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { status.awaitConnected() }
        assertTrue(waiting.isActive)

        status.value = ChainStatus(state = TunnelState.Connected)

        waiting.await()
    }

    @Test
    fun `an error fails the wait with its detail`() = runTest {
        val detail = UiText.Dynamic("no traffic")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { status.awaitConnected() } }

        status.value = ChainStatus(state = TunnelState.Error, detail = detail)

        val error = assertFailsWith<TunnelConnectException> { waiting.await().getOrThrow() }
        assertEquals(TunnelState.Error, error.state)
        assertSame(detail, error.detail)
    }

    @Test
    fun `the user's disconnect during the wait fails it`() = runTest {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { status.awaitConnected() } }

        status.value = ChainStatus(state = TunnelState.Disconnected)

        val error = assertFailsWith<TunnelConnectException> { waiting.await().getOrThrow() }
        assertEquals(TunnelState.Disconnected, error.state)
    }

    @Test
    fun `a tunnel that stays connecting fails at the timeout`() = runTest {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { status.awaitConnected(10.seconds) } }

        advanceTimeBy(9.seconds)
        runCurrent()
        assertTrue(waiting.isActive)
        advanceTimeBy(2.seconds)

        val error = assertFailsWith<TunnelConnectException> { waiting.await().getOrThrow() }
        assertEquals(TunnelState.Connecting, error.state)
    }

    @Test
    fun `a reload waits for the new engine, not the connected one it replaced`() = runTest {
        status.value = ChainStatus(state = TunnelState.Connected)
        status.value = ChainStatus(state = TunnelState.Connecting)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { status.awaitConnected() }
        runCurrent()
        assertTrue(waiting.isActive)

        status.value = ChainStatus(state = TunnelState.Connected)

        waiting.await()
    }

    @Test
    fun `a wait begun on a leftover connected state returns at once`() = runTest {
        status.value = ChainStatus(state = TunnelState.Connected)

        status.awaitConnected()
    }
}
