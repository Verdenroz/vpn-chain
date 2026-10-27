package com.verdenroz.vpnchain.core.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals

class ClashDestinationFeedTest {

    @Test
    fun `hosts are read from live connections and deduplicated`() {
        val body = """
            {"connections":[
              {"metadata":{"type":"tun/tun-in","host":"www.example.com","destinationIP":"1.1.1.1"}},
              {"metadata":{"type":"tun/tun-in","host":"www.example.com","destinationIP":"1.1.1.1"}},
              {"metadata":{"type":"tun/tun-in","host":"other.org","destinationIP":"2.2.2.2"}}
            ]}
        """

        assertEquals(setOf("www.example.com", "other.org"), ClashDestinationFeed.parseHosts(body))
    }

    @Test
    fun `connections with no sniffed name and the app's own probes are left out`() {
        val body = """
            {"connections":[
              {"metadata":{"type":"tun/tun-in","host":"","destinationIP":"1.1.1.1"}},
              {"metadata":{"type":"mixed/probe-relay","host":"www.example.com"}},
              {"metadata":{"type":"mixed/probe-warp","host":"www.example.com"}}
            ]}
        """

        assertEquals(emptySet(), ClashDestinationFeed.parseHosts(body))
    }

    @Test
    fun `a null connection list reads as empty`() {
        assertEquals(emptySet(), ClashDestinationFeed.parseHosts("""{"connections":null}"""))
    }
}
