package com.verdenroz.vpnchain.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortRaceTest {

    @Test
    fun `a picked loopback port taken first is a lost race`() {
        val log = "FATAL[0000] start service: start inbound: start inbound/mixed[probe-relay]: " +
            "listen tcp 127.0.0.1:41001: bind: address already in use"

        assertTrue(isLostPortRace(log))
    }

    @Test
    fun `the user's own proxy port being taken is not`() {
        val log = "start inbound/mixed[proxy-in]: listen tcp 127.0.0.1:1080: bind: address already in use"

        assertFalse(isLostPortRace(log, fixedPort = 1080))
    }

    @Test
    fun `any other start failure is not`() {
        assertFalse(isLostPortRace("start service: initialize outbound[entry-hop]: invalid key"))
    }

    @Test
    fun `a cached blocklist sing-box cannot read is told apart from other failures`() {
        val failure = "initialize rule-set[0]: restore cached rule-set: read rule[0]: unknown rule item type: 112, last type: 10"

        assertTrue(isUnreadableRuleSetCache(failure))
        assertFalse(isUnreadableRuleSetCache("initialize rule-set[3]: open learned-warp.json: no such file or directory"))
    }
}
