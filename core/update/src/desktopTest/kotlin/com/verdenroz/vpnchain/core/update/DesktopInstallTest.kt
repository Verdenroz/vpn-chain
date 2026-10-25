package com.verdenroz.vpnchain.core.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopInstallTest {
    private val temp = Files.createTempDirectory("vpn-chain-install")
    private val root = temp.resolve("data/vpn-chain")
    private val install = DesktopInstall(root)

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private fun tarball(name: String, launcher: Boolean = true, marker: String = name): Path {
        val tree = temp.resolve("tree-$name")
        val bin = Files.createDirectories(tree.resolve("vpn-chain/bin"))
        if (launcher) {
            val file = Files.writeString(bin.resolve("vpn-chain"), "#!/bin/sh\necho $marker\n")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        } else {
            Files.writeString(bin.resolve("other"), "x")
        }
        val archive = temp.resolve("$name.tar.gz")
        check(ProcessBuilder("tar", "-czf", archive.toString(), "-C", tree.toString(), "vpn-chain").start().waitFor() == 0)
        return archive
    }

    private fun currentVersion(): String = Files.readSymbolicLink(root.resolve("current")).fileName.toString()

    @Test
    fun `installs a tarball and points current at it`() {
        install.install(tarball("a"), "0.1.7")

        assertEquals("0.1.7", currentVersion())
        assertTrue(Files.isExecutable(install.launcher))
        assertEquals("echo a", Files.readAllLines(install.launcher)[1])
    }

    @Test
    fun `an update keeps the previous version and drops older ones`() {
        install.install(tarball("a"), "0.1.7")
        install.install(tarball("b"), "0.1.8")
        install.install(tarball("c"), "0.1.9")

        assertEquals("0.1.9", currentVersion())
        assertEquals(setOf("0.1.8", "0.1.9"), Files.list(root.resolve("versions")).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
    }

    @Test
    fun `a tarball with no launcher changes nothing`() {
        install.install(tarball("a"), "0.1.7")

        assertFailsWith<IOException> { install.install(tarball("b", launcher = false), "0.1.8") }

        assertEquals("0.1.7", currentVersion())
        assertEquals(listOf("0.1.7"), Files.list(root.resolve("versions")).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun `a file that is not a tarball changes nothing`() {
        install.install(tarball("a"), "0.1.7")
        val junk = Files.writeString(temp.resolve("junk.tar.gz"), "not a tarball")

        assertFailsWith<IOException> { install.install(junk, "0.1.8") }

        assertEquals("0.1.7", currentVersion())
    }

    @Test
    fun `manages only launchers inside its own versions`() {
        install.install(tarball("a"), "0.1.7")
        val inside = root.resolve("versions/0.1.7/vpn-chain/bin/vpn-chain").toString()
        val viaCurrent = install.launcher.toString()
        val elsewhere = Files.createFile(temp.resolve("dev-launcher")).toString()

        assertTrue(install.manages(inside))
        assertTrue(install.manages(viaCurrent))
        assertFalse(install.manages(elsewhere))
        assertFalse(install.manages(null))
    }

    @Test
    fun `manages nothing before anything is installed`() {
        val launcher = Files.createFile(temp.resolve("dev-launcher")).toString()

        assertFalse(install.manages(launcher))
    }
}
