package com.verdenroz.vpnchain.core.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ReplacingWriteTest {

    private val dir = Files.createTempDirectory("replacing-write")

    @Test
    fun `a symlink planted at the target is replaced, not written through`() {
        val victim = Files.writeString(dir.resolve("victim"), "precious")
        val target = dir.resolve("relay.render")
        Files.createSymbolicLink(target, victim)

        writeReplacing(target, "2", "rw-r-----")

        assertFalse(Files.isSymbolicLink(target))
        assertEquals("2", Files.readString(target))
        assertEquals("precious", Files.readString(victim))
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test
    fun `no staged file is left behind`() {
        writeReplacing(dir.resolve("learned-open.json"), "{}", "rw-------")

        assertEquals(listOf("learned-open.json"), Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun `an unwritable directory is an error`() {
        val locked = Files.createDirectory(dir.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))

        assertFailsWith<IOException> { writeReplacing(locked.resolve("x.json"), "{}", "rw-------") }
    }
}
