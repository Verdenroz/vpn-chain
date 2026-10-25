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

    private fun tarball(
        name: String,
        launcher: Boolean = true,
        marker: String = name,
        links: Map<String, String> = emptyMap(),
        tarArgs: List<String> = emptyList(),
    ): Path {
        val tree = temp.resolve("tree-$name")
        val bin = Files.createDirectories(tree.resolve("vpn-chain/bin"))
        if (launcher) {
            val file = Files.writeString(bin.resolve("vpn-chain"), "#!/bin/sh\necho $marker\n")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        } else {
            Files.writeString(bin.resolve("other"), "x")
        }
        links.forEach { (link, target) -> Files.createSymbolicLink(tree.resolve("vpn-chain").resolve(link), Path.of(target)) }
        val archive = temp.resolve("$name.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", archive.toString(), *tarArgs.toTypedArray(), "-C", tree.toString(), "vpn-chain").start()
        check(tar.waitFor() == 0)
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
    fun `keeps a symlink that stays inside the release`() {
        install.install(tarball("a", links = mapOf("bin/again" to "vpn-chain")), "0.1.7")

        assertEquals("0.1.7", currentVersion())
    }

    @Test
    fun `refuses a symlink that leaves the release`() {
        install.install(tarball("a"), "0.1.7")
        val outside = mapOf("bin/escape" to "../../../../outside", "bin/absolute" to temp.toString())

        for ((link, target) in outside) {
            assertFailsWith<IOException> { install.install(tarball("b-${link.substringAfter('/')}", links = mapOf(link to target)), "0.1.8") }
        }

        assertEquals("0.1.7", currentVersion())
        assertFalse(Files.exists(root.resolve("versions/0.1.8")))
    }

    @Test
    fun `refuses a symlink that only stays inside on paper`() {
        install.install(tarball("a"), "0.1.7")
        val outside = Files.createDirectories(temp.resolve("outside")).toRealPath()
        // From versions/.partial-0.1.8/vpn-chain, as many `..` as it takes to reach `/`.
        val depth = temp.toRealPath().nameCount + 5
        val climb = "s/".repeat(depth - 1) + "../".repeat(depth) + outside.toString().removePrefix("/")

        assertFailsWith<IOException> { install.install(tarball("b", links = mapOf("s" to ".", "l" to climb)), "0.1.8") }

        assertEquals("0.1.7", currentVersion())
        assertFalse(Files.exists(root.resolve("versions/0.1.8")))
    }

    @Test
    fun `refuses a dangling symlink`() {
        assertFailsWith<IOException> { install.install(tarball("a", links = mapOf("bin/gone" to "missing")), "0.1.7") }

        assertFalse(Files.exists(root.resolve("current")))
    }

    @Test
    fun `refuses entries outside the release`() {
        install.install(tarball("a"), "0.1.7")
        val escapes = listOf("--transform=s,^vpn-chain,../vpn-chain,", "--transform=s,^vpn-chain,$temp/abs/vpn-chain,")

        escapes.forEachIndexed { i, transform ->
            assertFailsWith<IOException> { install.install(tarball("b$i", tarArgs = listOf("-P", transform)), "0.1.8") }
        }

        assertEquals("0.1.7", currentVersion())
        assertFalse(Files.exists(temp.resolve("data/vpn-chain/vpn-chain")))
        assertFalse(Files.exists(temp.resolve("abs")))
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
