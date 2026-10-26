package com.verdenroz.vpnchain.core.tunnel

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

private const val SECRET = "s3cret"

private val CONNECTIONS = """
    {"connections":[
      {"id":"a","metadata":{"type":"tun/tun-in","host":"www.bank.example"}},
      {"id":"b","metadata":{"type":"tun/tun-in","host":"WWW.BANK.EXAMPLE"}},
      {"id":"c","metadata":{"type":"tun/tun-in","host":"bank.example"}},
      {"id":"d","metadata":{"type":"tun/tun-in","host":"old.reddit.com"}},
      {"id":"e","metadata":{"type":"tun/tun-in","host":"reddit.com"}},
      {"id":"f","metadata":{"type":"tun/tun-in","host":"notreddit.com"}},
      {"id":"g","metadata":{"type":"mixed/probe-relay","host":"www.bank.example"}},
      {"id":"h","metadata":{"type":"tun/tun-in","host":""}}
    ]}
"""

/** A loopback clash API that lists [CONNECTIONS] and records each DELETE. */
private class FakeClashApi(private val deleteStatus: Int = 204) : AutoCloseable {
    val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/connections") { exchange ->
            val status = when {
                exchange.requestHeaders.getFirst("Authorization") != "Bearer $SECRET" -> 401
                exchange.requestMethod == "GET" -> 200
                else -> deleteStatus.also { deleted += exchange.requestURI.path.substringAfterLast('/') }
            }
            val body = if (status == 200) CONNECTIONS.toByteArray() else ByteArray(0)
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }

    val config = """{"experimental":{"clash_api":{"external_controller":"127.0.0.1:${server.address.port}","secret":"$SECRET"}}}"""

    override fun close() = server.stop(0)
}

class ClashConnectionCloserTest {

    @Test
    fun `an exact host closes only its own connections`() = runTest {
        FakeClashApi().use { api ->
            ClashConnectionCloser(MutableStateFlow(api.config)).close(HostMatch("www.bank.example", subdomains = false))

            assertEquals(listOf("a", "b"), api.deleted)
        }
    }

    @Test
    fun `a domain-level match closes the domain and the names under it`() = runTest {
        FakeClashApi().use { api ->
            ClashConnectionCloser(MutableStateFlow(api.config)).close(HostMatch("reddit.com", subdomains = true))

            assertEquals(listOf("d", "e"), api.deleted)
        }
    }

    @Test
    fun `nothing is closed while the engine is down`() = runTest {
        FakeClashApi().use { api ->
            ClashConnectionCloser(MutableStateFlow(null)).close(HostMatch("reddit.com", subdomains = true))

            assertTrue(api.deleted.isEmpty())
        }
    }

    @Test
    fun `a refused close is thrown to the caller`() = runTest {
        FakeClashApi(deleteStatus = 500).use { api ->
            assertFailsWith<IOException> {
                ClashConnectionCloser(MutableStateFlow(api.config)).close(HostMatch("bank.example", subdomains = false))
            }
        }
    }
}
