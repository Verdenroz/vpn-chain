package com.verdenroz.vpnchain.core.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

private const val LAUNCHER = "vpn-chain/bin/vpn-chain"

/**
 * Unpacks release tarballs into `versions/<version>` under [root], as `cli/install-desktop.sh` does, and points the
 * `current` symlink at the one just installed. Every failure leaves `current` where it was.
 */
class DesktopInstall(private val root: Path) {
    private val versions = root.resolve("versions")
    private val current = root.resolve("current")

    val launcher: Path = current.resolve(LAUNCHER)

    /** Whether the launcher at [appPath] lives in this install, as opposed to a development run or a system package. */
    fun manages(appPath: String?): Boolean =
        appPath != null && Files.isDirectory(versions) && Path.of(appPath).toRealPath().startsWith(versions.toRealPath())

    @Throws(IOException::class)
    fun install(tarball: Path, version: String) {
        Files.createDirectories(versions)
        val partial = versions.resolve(".partial-$version")
        try {
            partial.toFile().deleteRecursively()
            Files.createDirectories(partial)
            extract(tarball, partial)
            if (!Files.isExecutable(partial.resolve(LAUNCHER))) throw IOException("The update has no runnable launcher")
            val target = versions.resolve(version)
            target.toFile().deleteRecursively()
            Files.move(partial, target, ATOMIC_MOVE)
            val previous = if (Files.isSymbolicLink(current)) Files.readSymbolicLink(current).fileName else null
            point(target)
            prune(keep = setOfNotNull(target.fileName, previous))
        } finally {
            partial.toFile().deleteRecursively()
        }
    }

    private fun extract(tarball: Path, into: Path) {
        val tar = ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", into.toString()).redirectErrorStream(true).start()
        val output = tar.inputStream.bufferedReader().readText().trim()
        if (tar.waitFor() != 0) throw IOException(output.ifEmpty { "tar could not unpack the update" })
    }

    private fun point(target: Path) {
        val link = root.resolve(".current-tmp")
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, Path.of("versions", target.fileName.toString()))
        Files.move(link, current, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    /** Keeps the new version and the one it replaces, which is the copy still running. */
    private fun prune(keep: Set<Path>) {
        Files.newDirectoryStream(versions).use { entries ->
            entries.filter { it.fileName !in keep }.forEach { it.toFile().deleteRecursively() }
        }
    }
}
