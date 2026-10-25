package com.verdenroz.vpnchain.core.common

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateStorageTest {

    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private fun loosen(file: File, perms: String) =
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))

    private fun home() = Files.createTempDirectory("private-storage").toFile()

    @Test
    fun `config dir is created owner-only`() {
        val dir = PrivateStorage.prepareConfigDir(home())

        assertEquals("rwx------", mode(dir))
    }

    @Test
    fun `existing loose dir and its files are tightened`() {
        val home = home()
        val dir = File(home, ".config/vpn-chain").apply { mkdirs() }
        loosen(dir, "rwxr-xr-x")
        val keys = File(dir, "vpnchain.preferences_pb").apply { writeText("k") }
        val backup = File(dir, "secrets.env.bak").apply { writeText("b") }
        listOf(keys, backup).forEach { loosen(it, "rw-r--r--") }

        PrivateStorage.prepareConfigDir(home)

        assertEquals("rwx------", mode(dir))
        assertEquals("rw-------", mode(keys))
        assertEquals("rw-------", mode(backup))
    }

    @Test
    fun `symlinked files are not followed`() {
        val home = home()
        val dir = File(home, ".config/vpn-chain").apply { mkdirs() }
        val outside = File(home, "shared.txt").apply { writeText("s") }
        loosen(outside, "rw-r--r--")
        Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())

        PrivateStorage.prepareConfigDir(home)

        assertEquals("rw-r--r--", mode(outside))
    }

    @Test
    fun `temp file is owner-only`() {
        val file = PrivateStorage.createTempFile(PrivateStorage.prepareConfigDir(home()), "vpn-chain-", ".json")

        assertEquals("rw-------", mode(file))
    }

    @Test
    fun `temp file without a dir is owner-only`() {
        val file = PrivateStorage.createTempFile(null, "vpn-chain-", ".json").apply { deleteOnExit() }

        assertEquals("rw-------", mode(file))
    }
}
