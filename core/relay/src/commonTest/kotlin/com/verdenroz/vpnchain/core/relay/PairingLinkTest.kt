package com.verdenroz.vpnchain.core.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val KEY = "pubKeyPUBKEYpubKeyPUBKEYpubKeyPUBKEYpubKeyP"
private const val TOKEN = "tokenTOKENtokenTOKENtokenTOKENtokenTOKENtok"
private const val UUID = "11111111-1111-4111-8111-111111111111"

internal val LINK = PairingLink(
    host = "203.0.113.10",
    port = 443,
    sni = "www.example.com",
    realityPublicKey = KEY,
    shortId = "0123456789abcdef",
    uuid = UUID,
    deviceName = "laptop",
    token = TOKEN,
    expires = 1_793_001_800,
)

class PairingLinkTest {

    private fun link(host: String = "203.0.113.10", extra: String = "") =
        "vpnchain://pair?v=1&h=$host&p=443&sni=www.example.com&pbk=$KEY&sid=ab12&id=$UUID&n=laptop&t=$TOKEN&e=1793001800$extra"

    private fun refusal(text: String) = assertIs<PairingException>(PairingLink.parse(text).exceptionOrNull())

    @Test
    fun `a link survives format and parse`() {
        assertEquals(LINK, PairingLink.parse(LINK.format()).getOrThrow())
    }

    @Test
    fun `an ipv6 host is bracketed on the wire and bare in the link`() {
        val v6 = LINK.copy(host = "2001:db8::1")

        assertTrue("h=%5B2001%3Adb8%3A%3A1%5D" in v6.format())
        assertEquals(v6, PairingLink.parse(v6.format()).getOrThrow())
        assertEquals("2001:db8::1", PairingLink.parse(link(host = "[2001:db8::1]")).getOrThrow().host)
    }

    @Test
    fun `a dns host and surrounding whitespace parse`() {
        val parsed = PairingLink.parse("  ${link(host = "relay.example.com")}\n").getOrThrow()

        assertEquals("relay.example.com", parsed.host)
    }

    @Test
    fun `parameter order is free and unknown parameters are ignored`() {
        val reordered = "vpnchain://pair?e=1793001800&t=$TOKEN&n=laptop&id=$UUID&sid=ab12&pbk=$KEY&sni=www.example.com&p=443&h=203.0.113.10&x=1&v=1"

        assertEquals("203.0.113.10", PairingLink.parse(reordered).getOrThrow().host)
    }

    @Test
    fun `percent encoded values decode`() {
        val encoded = link().replace("n=laptop", "n=%6Captop").replace("sni=www.example.com", "sni=www%2Eexample%2Ecom")

        assertEquals(LINK.copy(shortId = "ab12"), PairingLink.parse(encoded).getOrThrow())
    }

    @Test
    fun `a newer version is refused with a message that says to update`() {
        val error = refusal(link().replace("v=1", "v=2"))

        assertEquals(PairingException.Reason.UnsupportedVersion, error.reason)
        assertTrue("Update the app" in error.message.orEmpty())
    }

    @Test
    fun `text that is not a link is refused`() {
        assertEquals(PairingException.Reason.NotALink, refusal("VLESS_UUID=abc").reason)
        assertEquals(PairingException.Reason.NotALink, refusal("https://example.com/?v=1").reason)
    }

    @Test
    fun `a missing or malformed field is refused`() {
        val broken = listOf(
            link().replace("&t=$TOKEN", ""),
            link(host = "999.1.1.1"),
            link(host = "bad host"),
            link(host = "[example.com]"),
            link().replace("p=443", "p=0"),
            link().replace("p=443", "p=70000"),
            link().replace("sid=ab12", "sid=xyz"),
            link().replace("sid=ab12", "sid=abc"),
            link().replace("id=$UUID", "id=not-a-uuid"),
            link().replace("n=laptop", "n=Laptop"),
            link().replace("n=laptop", "n=a%20b"),
            link().replace("e=1793001800", "e=soon"),
            link().replace("t=$TOKEN", "t=short"),
            link().replace("pbk=$KEY", "pbk=short"),
            link(extra = "&p=80"),
            link().replace("h=203.0.113.10", "h=%zz"),
            link().replace("v=1&", ""),
        )

        for (text in broken) {
            assertEquals(PairingException.Reason.Invalid, refusal(text).reason, text)
        }
    }

    @Test
    fun `a link expires at its expiry`() {
        assertFalse(LINK.isExpired(LINK.expires - 1))
        assertTrue(LINK.isExpired(LINK.expires))
    }

    @Test
    fun `toString and refusals show the host and no secret`() {
        val shown = LINK.toString() + refusal(link().replace("t=$TOKEN", "t=$TOKEN-extra")).message

        assertEquals("PairingLink(host=203.0.113.10)", LINK.toString())
        for (secret in listOf(TOKEN, KEY, UUID, "0123456789abcdef", "laptop")) {
            assertFalse(secret in shown, secret)
        }
    }

    @Test
    fun `a pasted link is recognised before it is parsed`() {
        assertTrue(PairingLink.looksLikeLink("\n  vpnchain://pair?v=9"))
        assertFalse(PairingLink.looksLikeLink("VPS_IP=203.0.113.10"))
    }
}
