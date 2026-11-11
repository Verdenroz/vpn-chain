package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChainProbeTest {

    private val targets = listOf("a", "b")

    @Test
    fun `returns false at the bound when every target hangs`() = runTest {
        val probe = ChainProbe(backgroundScope, targets) { _, _ -> awaitCancellation() }

        val carries = probe.carriesTraffic(timeoutMs = 5_000)

        assertFalse(carries)
        assertEquals(11_000L, currentTime)
    }

    @Test
    fun `one hung target does not delay the other answering`() = runTest {
        val probe = ChainProbe(backgroundScope, targets) { url, _ ->
            if (url == "a") awaitCancellation()
            delay(300)
            true
        }

        assertTrue(probe.carriesTraffic(timeoutMs = 5_000))
        assertEquals(300L, currentTime)
    }

    @Test
    fun `fails early when every target answers no`() = runTest {
        val probe = ChainProbe(backgroundScope, targets) { _, _ ->
            delay(200)
            false
        }

        assertFalse(probe.carriesTraffic(timeoutMs = 5_000))
        assertEquals(200L, currentTime)
    }

    @Test
    fun `callers during an in-flight probe share it`() = runTest {
        var started = 0
        val probe = ChainProbe(backgroundScope, targets) { _, _ ->
            started++
            awaitCancellation()
        }

        val callers = List(5) { async { probe.carriesTraffic(timeoutMs = 5_000) } }
        callers.forEach { assertFalse(it.await()) }

        assertEquals(targets.size, started)
    }

    @Test
    fun `a caller on another budget does not join the flight`() = runTest {
        var started = 0
        val probe = ChainProbe(backgroundScope, targets) { _, _ ->
            started++
            awaitCancellation()
        }

        val health = async { probe.carriesTraffic(timeoutMs = 5_000) }
        val readiness = async { probe.carriesTraffic(timeoutMs = 3_000) }

        assertFalse(readiness.await())
        assertEquals(7_000L, currentTime)
        assertFalse(health.await())
        assertEquals(2 * targets.size, started)
    }

    @Test
    fun `the bound cancels the probes it abandons`() = runTest {
        var cancelled = 0
        val probe = ChainProbe(backgroundScope, targets) { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }

        assertFalse(probe.carriesTraffic(timeoutMs = 5_000))
        runCurrent()

        assertEquals(targets.size, cancelled)
    }

    @Test
    fun `a probe stuck past its bound does not capture the next call`() = runTest {
        var started = 0
        val probe = ChainProbe(backgroundScope, targets) { _, _ ->
            started++
            withContext(NonCancellable) { delay(60_000) }
            false
        }

        assertFalse(probe.carriesTraffic(timeoutMs = 5_000))
        assertEquals(11_000L, currentTime)
        assertFalse(probe.carriesTraffic(timeoutMs = 5_000))

        assertEquals(2 * targets.size, started)
        assertEquals(22_000L, currentTime)
    }

    @Test
    fun `a finished probe is not reused by the next caller`() = runTest {
        var started = 0
        val probe = ChainProbe(backgroundScope, listOf("a")) { _, _ ->
            started++
            true
        }

        probe.carriesTraffic()
        delay(10_000)
        probe.carriesTraffic()

        assertEquals(2, started)
    }
}
