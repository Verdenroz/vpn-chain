package com.verdenroz.vpnchain.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WireTextTest {

    @Test
    fun `a resource key resolves to its text with arguments`() = runTest {
        val text = WireText.Resource("tunnel_error_cannot_launch", listOf("sing-box")).resolve()
        assertEquals("cannot launch 'sing-box': is sing-box installed and on PATH?", text)
    }

    @Test
    fun `an unknown key comes back as itself`() = runTest {
        assertEquals("no_such_key", WireText.Resource("no_such_key").resolve())
    }

    @Test
    fun `a resource environment that cannot be built falls back to the key`() = runTest {
        val text = WireText.Resource("tunnel_status_tun").resolveWith { throw NoSuchMethodException("ResourceEnvironment.<init>") }
        assertEquals("tunnel_status_tun", text)
    }

    @Test
    fun `plain text passes through`() = runTest {
        assertEquals("as is", WireText.Plain("as is").resolve())
    }
}
