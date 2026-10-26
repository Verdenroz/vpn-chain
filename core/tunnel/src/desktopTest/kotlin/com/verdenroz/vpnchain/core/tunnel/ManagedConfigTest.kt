package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagedConfigTest {

    private val dir = Files.createTempDirectory("managed-config")
    private val config = dir.resolve("config.json")
    private val stamp = dir.resolve("relay.render")

    @Test
    fun `a symlink planted at the config is replaced, not written through`() {
        val victim = Files.writeString(dir.resolve("victim"), "precious")
        Files.createSymbolicLink(config, victim)

        writeManagedConfig(config, stamp, "{\"outbounds\":[]}")

        assertFalse(Files.isSymbolicLink(config))
        assertEquals("{\"outbounds\":[]}", Files.readString(config))
        assertEquals("precious", Files.readString(victim))
    }

    @Test
    fun `the config and stamp are group-readable and nothing more`() {
        writeManagedConfig(config, stamp, "{}")

        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(stamp)))
        assertEquals(SingBoxConfigFactory.RENDER_SCHEMA.toString(), Files.readString(stamp))
    }
}
