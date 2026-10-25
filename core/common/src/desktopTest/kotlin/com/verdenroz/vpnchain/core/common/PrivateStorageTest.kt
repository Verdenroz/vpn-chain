package com.verdenroz.vpnchain.core.common

import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class PrivateStorageTest {

    @BeforeTest
    fun requirePosix() = assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())

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

    @Test
    fun `a file the repair cannot chmod does not stop the dir from resolving`() {
        val home = home()
        val dir = File(home, ".config/vpn-chain").apply { mkdirs() }
        val rootOwned = File(dir, "singbox-cache.db").apply { writeText("c") }
        val keys = File(dir, "vpnchain.preferences_pb").apply { writeText("k") }
        listOf(rootOwned, keys).forEach { loosen(it, "rw-r--r--") }

        val resolved = PrivateStorage.prepareConfigDir(home, setPerms = { path, perms ->
            if (path.fileName.toString() == rootOwned.name) throw IOException("not owner")
            Files.setPosixFilePermissions(path, perms)
        })

        assertEquals(dir, resolved)
        assertEquals("rw-r--r--", mode(rootOwned))
        assertEquals("rw-------", mode(keys))
    }

    @Test
    fun `without posix the dir is created and permissions are left alone`() {
        val home = home()
        val dir = File(home, ".config/vpn-chain").apply { mkdirs() }
        loosen(dir, "rwxr-xr-x")

        PrivateStorage.prepareConfigDir(home, posix = false, setPerms = { _, _ -> throw IOException("unused") })

        assertTrue(dir.isDirectory)
        assertEquals("rwxr-xr-x", mode(dir))
    }

    @Test
    fun `private dir and file are owner-only and an existing file keeps its content`() {
        val dir = PrivateStorage.privateDir(File(home(), "run/vpn-chain"))
        val log = File(dir, "relay.log")

        PrivateStorage.ensurePrivateFile(log)
        log.writeText("keep")
        loosen(log, "rw-r--r--")
        PrivateStorage.ensurePrivateFile(log)

        assertEquals("rwx------", mode(dir))
        assertEquals("rw-------", mode(log))
        assertEquals("keep", log.readText())
    }
}
