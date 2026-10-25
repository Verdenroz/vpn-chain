package com.verdenroz.vpnchain.core.warp.probe

import kotlin.test.Test
import kotlin.test.assertEquals

private const val REDDIT_POW_INTERSTITIAL = """HTTP/2 200
content-type: text/html
cache-control: private, no-store
server: snooserv

<!DOCTYPE html>
<html lang="en"><head><title>Reddit</title></head><body>
<main><div class="logo"></div></main>
<form hidden method="GET" action="/">
<input type="hidden" name="solution" />
<input type="hidden" name="js_challenge" value="1"/>
<input type="hidden" name="jsc_token" value="REDACTED"/>
<input type="hidden" name="jsc_orig_r" value=""/>
</form></body></html>
"""

private const val REDDIT_BLOCK_PAGE = """HTTP/2 403
content-type: text/html
server: snooserv

<body class=theme-beta><div><div class="font-bold text-24">You've been blocked by network security.</div>
<div class="text-16"><div class=pt-md>If you think you've been blocked by mistake, file a ticket below and we'll look into it.</div></div></div></body>
"""

private const val CLOUDFLARE_CHALLENGE = """HTTP/2 403
cf-mitigated: challenge
content-type: text/html; charset=UTF-8
server: cloudflare

<!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title></head>
<body><script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script></body></html>
"""

private const val ORDINARY_PAGE = """HTTP/2 200
content-type: text/html
server: nginx

<!DOCTYPE html><html><head><title>Example</title></head><body><h1>Hello</h1></body></html>
"""

private fun response(sample: String, complete: Boolean = true): ProbeResponse =
    checkNotNull(ProbeResponse.parse(sample.replace("\n", "\r\n"), complete))

private fun verdict(status: Int, body: String = "", complete: Boolean = true, headers: Map<String, String> = emptyMap()) =
    SiteClassifier.classify(ProbeResponse(status, headers, body, complete))

class SiteClassifierTest {

    @Test
    fun `reddit's proof-of-work interstitial is blocked even though it answers 200`() {
        assertEquals(
            ExitVerdict.Blocked(BlockReason.Challenge),
            SiteClassifier.classify(response(REDDIT_POW_INTERSTITIAL)),
        )
    }

    @Test
    fun `reddit's network security page is blocked and named`() {
        assertEquals(
            ExitVerdict.Blocked(BlockReason.NetworkSecurity),
            SiteClassifier.classify(response(REDDIT_BLOCK_PAGE)),
        )
    }

    @Test
    fun `a cloudflare challenge is blocked`() {
        assertEquals(
            ExitVerdict.Blocked(BlockReason.Challenge),
            SiteClassifier.classify(response(CLOUDFLARE_CHALLENGE)),
        )
    }

    @Test
    fun `an ordinary page is open`() {
        assertEquals(ExitVerdict.Open, SiteClassifier.classify(response(ORDINARY_PAGE)))
    }

    @Test
    fun `refusing statuses are blocked with their code as the reason`() {
        assertEquals(ExitVerdict.Blocked(BlockReason.Forbidden), verdict(403))
        assertEquals(ExitVerdict.Blocked(BlockReason.RateLimited), verdict(429))
        assertEquals(ExitVerdict.Blocked(BlockReason.LegalBlock), verdict(451))
    }

    @Test
    fun `auth walls, missing pages and redirects are open`() {
        listOf(200, 301, 302, 401, 404).forEach { assertEquals(ExitVerdict.Open, verdict(it), "$it") }
    }

    @Test
    fun `server errors say nothing about the address`() {
        listOf(500, 502, 503).forEach { assertEquals(ExitVerdict.Inconclusive, verdict(it), "$it") }
    }

    @Test
    fun `a long page that merely quotes a marker is open`() {
        val article = "He wrote: you've been blocked by network security. " + "x".repeat(1000)

        assertEquals(ExitVerdict.Open, verdict(200, article, complete = false))
    }

    @Test
    fun `a marker on a truncated error page still names the reason`() {
        assertEquals(
            ExitVerdict.Blocked(BlockReason.NetworkSecurity),
            verdict(403, "blocked by network security", complete = false),
        )
    }

    @Test
    fun `the cloudflare mitigation header alone marks a challenge`() {
        assertEquals(
            ExitVerdict.Blocked(BlockReason.Challenge),
            verdict(200, headers = mapOf("cf-mitigated" to "challenge")),
        )
    }

    @Test
    fun `parsing keeps lowercased headers and the body after the blank line`() {
        val parsed = response(ORDINARY_PAGE)

        assertEquals(200, parsed.status)
        assertEquals("nginx", parsed.headers["server"])
        assertEquals(true, parsed.body.startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun `text that is not a response does not parse`() {
        assertEquals(null, ProbeResponse.parse("garbage", complete = true))
        assertEquals(null, ProbeResponse.parse("NOPE\r\n\r\nbody", complete = true))
    }
}
