package com.verdenroz.vpnchain.core.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs a downloaded package through the polkit action the .deb and the Arch package ship. The helper hashes its
 * own copy of the package before `apt` or `pacman` sees it, so the file that was checked is the file that is installed.
 */
class PackageInstall(
    private val root: Path = Path.of("/opt/vpn-chain"),
    private val pkexec: String = "pkexec",
) {
    val launcher: Path = root.resolve("bin/vpn-chain")

    private val helper: Path = root.resolve("libexec/vpn-chain-install-update")

    /** The manifest key of this package's format; the Arch package names its own, the .deb ships no marker. */
    val artifactKey: String =
        root.resolve("libexec/artifact-key").takeIf(Files::isRegularFile)?.let { Files.readString(it).trim() } ?: "linux-x64"

    /** Whether the launcher at [appPath] lives in the package, as opposed to a development run. */
    fun manages(appPath: String?): Boolean =
        appPath != null && Files.isDirectory(root) && Path.of(appPath).toRealPath().startsWith(root.toRealPath())

    @Throws(IOException::class)
    fun install(deb: Path, sha256: String) {
        val process = ProcessBuilder(pkexec, helper.toString(), sha256)
            .redirectInput(deb.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw IOException(output.trim().lines().last().ifEmpty { "The update could not be installed" })
        }
    }
}
