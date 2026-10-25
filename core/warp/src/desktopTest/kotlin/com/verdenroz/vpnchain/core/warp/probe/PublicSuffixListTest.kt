package com.verdenroz.vpnchain.core.warp.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicSuffixListTest {

    private val list = PublicSuffixList.bundled

    @Test
    fun `sibling hosts share their registrable domain`() {
        assertEquals("reddit.com", list.registrableDomain("old.reddit.com"))
        assertEquals("example.co.uk", list.registrableDomain("www.example.co.uk"))
        assertEquals("example.de", list.registrableDomain("WWW.Example.de."))
    }

    @Test
    fun `a tenant of a shared host is its own domain`() {
        assertEquals("user.github.io", list.registrableDomain("user.github.io"))
        assertEquals("d1234.cloudfront.net", list.registrableDomain("assets.d1234.cloudfront.net"))
    }

    @Test
    fun `a public suffix on its own has no registrable domain`() {
        assertNull(list.registrableDomain("github.io"))
        assertNull(list.registrableDomain("co.uk"))
        assertNull(list.registrableDomain("com"))
    }

    @Test
    fun `wildcard and exception rules`() {
        assertEquals("a.b.ck", list.registrableDomain("x.a.b.ck"))
        assertEquals("www.ck", list.registrableDomain("www.ck"))
        assertEquals("city.kawasaki.jp", list.registrableDomain("a.city.kawasaki.jp"))
    }

    @Test
    fun `internationalised rules match punycode hosts`() {
        assertEquals("example.xn--55qx5d.cn", list.registrableDomain("www.example.xn--55qx5d.cn"))
    }

    @Test
    fun `an unlisted suffix counts as one label`() {
        assertEquals("example.notatld", list.registrableDomain("a.example.notatld"))
    }
}
