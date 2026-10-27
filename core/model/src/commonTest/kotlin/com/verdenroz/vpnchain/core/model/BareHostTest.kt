package com.verdenroz.vpnchain.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BareHostTest {

    @Test
    fun `drops www when no other entry has that host`() {
        assertEquals("reddit.com", bareHost("www.reddit.com", listOf("www.reddit.com", "chatgpt.com")))
    }

    @Test
    fun `keeps www when the bare host is listed too`() {
        assertEquals("www.reddit.com", bareHost("www.reddit.com", listOf("www.reddit.com", "reddit.com")))
        assertEquals("reddit.com", bareHost("reddit.com", listOf("www.reddit.com", "reddit.com")))
    }

    @Test
    fun `leaves other subdomains alone`() {
        assertEquals("old.reddit.com", bareHost("old.reddit.com", listOf("old.reddit.com")))
    }
}
