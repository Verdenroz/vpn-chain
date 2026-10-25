package com.verdenroz.vpnchain.core.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath

class DesktopAppInstallerTest {
    private val temp = Files.createTempDirectory("vpn-chain-installer")
    private val install = DesktopInstall(temp.resolve("vpn-chain"))
    private val release = AppRelease("0.1.7", emptyMap())

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private fun tarball(): Path {
        val bin = Files.createDirectories(temp.resolve("tree/vpn-chain/bin"))
        val launcher = Files.writeString(bin.resolve("vpn-chain"), "#!/bin/sh\n")
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
        val archive = temp.resolve("a.tar.gz")
        check(ProcessBuilder("tar", "-czf", archive.toString(), "-C", temp.resolve("tree").toString(), "vpn-chain").start().waitFor() == 0)
        return archive
    }

    @Test
    fun `a development run is not offered updates`() {
        val installer = DesktopAppInstaller(install, appPath = null)

        assertNull(installer.artifactKey)
    }

    @Test
    fun `a launcher outside the managed install is not offered updates`() {
        install.install(tarball(), "0.1.6")
        val installer = DesktopAppInstaller(install, appPath = Files.createFile(temp.resolve("elsewhere")).toString())

        assertNull(installer.artifactKey)
    }

    @Test
    fun `the managed install is offered the linux build`() {
        install.install(tarball(), "0.1.6")
        val installer = DesktopAppInstaller(install, appPath = install.launcher.toString())

        assertEquals("linux-x64", installer.artifactKey)
    }

    @Test
    fun `installs the tarball and relaunches the new launcher`() = runTest {
        install.install(tarball(), "0.1.6")
        val relaunched = mutableListOf<Path>()
        val installer = DesktopAppInstaller(install, appPath = install.launcher.toString(), relaunch = { relaunched.add(it) })

        val result = installer.install(tarball().toOkioPath(), release)

        assertEquals(InstallResult.Started, result)
        assertEquals(listOf(install.launcher), relaunched)
        assertEquals("0.1.7", Files.readSymbolicLink(temp.resolve("vpn-chain/current")).fileName.toString())
    }

    @Test
    fun `a failed install reports the problem and does not relaunch`() = runTest {
        val relaunched = mutableListOf<Path>()
        val installer = DesktopAppInstaller(install, appPath = null, relaunch = { relaunched.add(it) })
        val junk = Files.writeString(temp.resolve("junk.tar.gz"), "not a tarball")

        val result = installer.install(junk.toOkioPath(), release)

        assertIs<InstallResult.Failed>(result)
        assertTrue(relaunched.isEmpty())
    }

    @Test
    fun `the relaunch waits for the process and drops the jpackage marker`() {
        val command = relaunchCommand(42, Path.of("/data/current/vpn-chain/bin/vpn-chain"), mapOf("HOME" to "/h", "_JPACKAGE_LAUNCHER" to "1"))

        assertEquals(listOf("sh", "-c"), command.command().take(2))
        assertEquals(listOf("sh", "42", "/data/current/vpn-chain/bin/vpn-chain"), command.command().drop(3))
        assertEquals(mapOf("HOME" to "/h"), command.environment().toMap())
    }
}
