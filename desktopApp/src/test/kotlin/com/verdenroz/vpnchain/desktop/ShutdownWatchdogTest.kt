package com.verdenroz.vpnchain.desktop

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownWatchdogTest {
    private fun sigterm(vararg args: String): Pair<Int, Long> {
        val java = "${System.getProperty("java.home")}/bin/java"
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            "com.verdenroz.vpnchain.desktop.ShutdownFixtureKt", *args,
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readLine()
        val begun = System.nanoTime()
        process.destroy()
        val exited = process.waitFor(10, TimeUnit.SECONDS)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
        if (!exited) process.destroyForcibly()
        assertTrue(exited, "the process outlived SIGTERM by more than 10s")
        return process.exitValue() to tookMs
    }

    @Test
    fun `a hook that never returns is cut off once the grace passes`() {
        val (code, tookMs) = sigterm("block")
        assertEquals(FORCED_EXIT_CODE, code)
        assertTrue(tookMs in FIXTURE_GRACE_MS..5_000, "halted after ${tookMs}ms")
    }

    @Test
    fun `a shutdown that finishes is not held for the grace`() {
        val (code, tookMs) = sigterm()
        assertEquals(SIGTERM_EXIT_CODE, code)
        assertTrue(tookMs < FIXTURE_GRACE_MS, "exited after ${tookMs}ms")
    }

    private companion object {
        const val SIGTERM_EXIT_CODE = 143
    }
}
