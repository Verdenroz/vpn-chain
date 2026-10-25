package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.config.isLostPortRace
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdoptedRelayTest {

    @Test
    fun `only this build's render schema is reused on adoption`() {
        assertTrue(renderedByThisBuild("${SingBoxConfigFactory.RENDER_SCHEMA}\n"))
        assertFalse(renderedByThisBuild(null))
        assertFalse(renderedByThisBuild(""))
        assertFalse(renderedByThisBuild("${SingBoxConfigFactory.RENDER_SCHEMA - 1}"))
    }

    @Test
    fun `a bind error from an earlier run is not this launch's port race`() {
        val log = Files.createTempFile("relay", ".log").toFile()
        try {
            log.writeText("FATAL start service: start inbound/mixed[probe-relay]: listen tcp 127.0.0.1:41001: bind: address already in use\n")
            val offset = log.length()
            log.appendText("INFO sing-box started\nFATAL start service: initialize outbound[entry-hop]: invalid key\n")

            assertFalse(isLostPortRace(logSince(log, offset)))
            assertTrue(isLostPortRace(logSince(log, 0)))
        } finally {
            log.delete()
        }
    }

    @Test
    fun `a log shorter than the offset reads as nothing new`() {
        val log = Files.createTempFile("relay", ".log").toFile()
        try {
            log.writeText("x")
            assertEquals("", logSince(log, 10))
        } finally {
            log.delete()
        }
    }
}
