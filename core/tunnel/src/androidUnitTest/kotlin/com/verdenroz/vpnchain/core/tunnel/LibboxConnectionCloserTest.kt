package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LibboxConnectionCloserTest {
    private val live = LiveConnections().apply {
        opened("a", "www.bank.example")
        opened("b", "bank.example")
        opened("c", "old.reddit.com")
        opened("d", "reddit.com")
        opened("e", "notreddit.com")
        opened("f", "")
    }
    private val closed = mutableListOf<String>()
    private val closer = LibboxConnectionCloser(live) { closed += it }

    @Test
    fun `an exact host closes only its own connections`() = runTest {
        closer.close(HostMatch("www.bank.example", subdomains = false))

        assertEquals(listOf("a"), closed)
    }

    @Test
    fun `a domain-level match closes the domain and the names under it`() = runTest {
        closer.close(HostMatch("reddit.com", subdomains = true))

        assertEquals(setOf("c", "d"), closed.toSet())
    }

    @Test
    fun `a connection the stream reported closed is not closed again`() = runTest {
        live.closed("a")

        closer.close(HostMatch("www.bank.example", subdomains = false))

        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a close the engine refuses is thrown to the caller`() = runTest {
        val refusing = LibboxConnectionCloser(live) { error("command server gone") }

        assertFailsWith<IllegalStateException> { refusing.close(HostMatch("bank.example", subdomains = false)) }
    }
}
