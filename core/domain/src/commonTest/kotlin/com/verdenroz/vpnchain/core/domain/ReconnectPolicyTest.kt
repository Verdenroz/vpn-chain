package com.verdenroz.vpnchain.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    @Test
    fun `first retry waits the base delay`() {
        assertEquals(ReconnectPolicy.BASE_DELAY_MS, ReconnectPolicy.delayForAttemptMillis(1))
    }

    @Test
    fun `each attempt doubles the wait`() {
        assertEquals(1_000L, ReconnectPolicy.delayForAttemptMillis(1))
        assertEquals(2_000L, ReconnectPolicy.delayForAttemptMillis(2))
        assertEquals(4_000L, ReconnectPolicy.delayForAttemptMillis(3))
        assertEquals(8_000L, ReconnectPolicy.delayForAttemptMillis(4))
        assertEquals(16_000L, ReconnectPolicy.delayForAttemptMillis(5))
    }

    @Test
    fun `backoff is capped so a long outage keeps a predictable cadence`() {
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttemptMillis(6))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttemptMillis(20))
    }

    /**
     * A tunnel down overnight reaches attempt counts that overflow a naive
     * `1000 shl (attempt - 1)` into negative delays — which `delay()` treats as
     * "no wait" and turns the backoff into a hot reconnect loop.
     */
    @Test
    fun `extreme attempt counts stay clamped instead of overflowing`() {
        listOf(64, 65, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val delay = ReconnectPolicy.delayForAttemptMillis(attempt)
            assertEquals(ReconnectPolicy.MAX_DELAY_MS, delay, "attempt $attempt")
            assertTrue(delay > 0, "attempt $attempt produced a non-positive delay")
        }
    }

    @Test
    fun `rejects a non-positive attempt because attempts are one-based`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy.delayForAttemptMillis(0) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy.delayForAttemptMillis(-1) }
    }
}
