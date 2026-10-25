package com.verdenroz.vpnchain.core.common

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateStorageTest {

    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private fun home() = Files.createTempDirectory("private-storage").toFile()

    @Test
    fun `config dir is created owner-only`() {
        val dir = PrivateStorage.configDir(home())

        assertEquals("rwx------", mode(dir))
    }

    @Test
    fun `existing loose dir and known files are tightened`() {
        val home = home()
        val dir = File(home, ".config/vpn-chain").apply { mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val keys = File(dir, "vpnchain.preferences_pb").apply { writeText("k") }
        val cache = File(dir, "singbox-cache.db").apply { writeText("c") }
        val unrelated = File(dir, "notes.txt").apply { writeText("n") }
        listOf(keys, cache, unrelated).forEach {
            Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }

        PrivateStorage.configDir(home)

        assertEquals("rwx------", mode(dir))
        assertEquals("rw-------", mode(keys))
        assertEquals("rw-------", mode(cache))
        assertEquals("rw-r--r--", mode(unrelated))
    }

    @Test
    fun `created file is owner-only and an existing one is left alone`() {
        val dir = PrivateStorage.configDir(home())
        val file = File(dir, "vpnchain.preferences_pb")

        PrivateStorage.createFile(file)
        file.writeText("keep")
        PrivateStorage.createFile(file)

        assertEquals("rw-------", mode(file))
        assertEquals("keep", file.readText())
    }

    @Test
    fun `temp file is owner-only`() {
        val file = PrivateStorage.createTempFile(PrivateStorage.configDir(home()), "vpn-chain-", ".json")

        assertEquals("rw-------", mode(file))
    }
}
