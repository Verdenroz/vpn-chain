package com.verdenroz.vpnchain.core.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath

class DesktopAppInstallerTest {
    private val temp = Files.createTempDirectory("vpn-chain-installer")
    private val root = Files.createDirectories(temp.resolve("opt/vpn-chain"))
    private val launcher = Files.createDirectories(root.resolve("bin")).resolve("vpn-chain").also { Files.createFile(it) }
    private val sha256 = "ab".repeat(32)
    private val release = AppRelease("0.1.7", mapOf("linux-x64" to ReleaseArtifact("vpn-chain.deb", 3, sha256)))

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private fun pkexec(body: String): String {
        val script = Files.writeString(temp.resolve("pkexec"), "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }

    private fun deb(): Path = Files.writeString(temp.resolve("vpn-chain.deb"), "deb")

    @Test
    fun `a development run is not offered updates`() {
        val installer = DesktopAppInstaller(PackageInstall(root), appPath = null)

        assertNull(installer.artifactKey)
    }

    @Test
    fun `a launcher outside the package is not offered updates`() {
        val installer = DesktopAppInstaller(PackageInstall(root), appPath = Files.createFile(temp.resolve("elsewhere")).toString())

        assertNull(installer.artifactKey)
    }

    @Test
    fun `a machine without the package is not offered updates`() {
        val installer = DesktopAppInstaller(PackageInstall(temp.resolve("missing")), appPath = launcher.toString())

        assertNull(installer.artifactKey)
    }

    @Test
    fun `the packaged app is offered the linux build`() {
        val installer = DesktopAppInstaller(PackageInstall(root), appPath = launcher.toString())

        assertEquals("linux-x64", installer.artifactKey)
    }

    @Test
    fun `a package that names its build is offered that build`() = runTest {
        Files.createDirectories(root.resolve("libexec"))
        Files.writeString(root.resolve("libexec/artifact-key"), "linux-x64-arch\n")
        val arch = AppRelease("0.1.7", mapOf("linux-x64-arch" to ReleaseArtifact("vpn-chain.pkg.tar.zst", 3, sha256)))
        val install = PackageInstall(root, pkexec("cat >/dev/null"))
        val installer = DesktopAppInstaller(install, appPath = launcher.toString(), relaunch = {})

        assertEquals("linux-x64-arch", installer.artifactKey)
        assertEquals(InstallResult.Started, installer.install(deb().toOkioPath(), arch))
    }

    @Test
    fun `hands the helper the hash and the package and relaunches`() = runTest {
        val record = temp.resolve("record")
        val install = PackageInstall(root, pkexec("echo \"\$@\" > $record; cat >> $record"))
        val relaunched = mutableListOf<Path>()
        val installer = DesktopAppInstaller(install, appPath = launcher.toString(), relaunch = { relaunched.add(it) })

        val result = installer.install(deb().toOkioPath(), release)

        assertEquals(InstallResult.Started, result)
        assertEquals(listOf(launcher), relaunched)
        assertEquals("${root.resolve("libexec/vpn-chain-install-update")} $sha256\ndeb", Files.readString(record))
    }

    @Test
    fun `a failed install reports the helper's last line and does not relaunch`() = runTest {
        val install = PackageInstall(root, pkexec("echo noise; echo 'the download does not match its SHA-256'; exit 1"))
        val relaunched = mutableListOf<Path>()
        val installer = DesktopAppInstaller(install, appPath = launcher.toString(), relaunch = { relaunched.add(it) })

        val result = installer.install(deb().toOkioPath(), release)

        assertEquals(InstallResult.Failed("the download does not match its SHA-256"), result)
        assertTrue(relaunched.isEmpty())
    }

    @Test
    fun `the relaunch waits for the process and drops the jpackage marker`() {
        val command = relaunchCommand(42, Path.of("/opt/vpn-chain/bin/vpn-chain"), mapOf("HOME" to "/h", "_JPACKAGE_LAUNCHER" to "1"))

        assertEquals(listOf("sh", "-c"), command.command().take(2))
        assertEquals(listOf("sh", "42", "/opt/vpn-chain/bin/vpn-chain"), command.command().drop(3))
        assertEquals(mapOf("HOME" to "/h"), command.environment().toMap())
    }
}
