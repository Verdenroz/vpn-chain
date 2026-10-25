package com.verdenroz.vpnchain.core.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

private const val LAUNCHER = "vpn-chain/bin/vpn-chain"

/**
 * Unpacks release tarballs into `versions/<version>` under [root], as `cli/install-desktop.sh` does, and points the
 * `current` symlink at the one just installed. Every failure leaves `current` where it was.
 */
@OptIn(ExperimentalPathApi::class)
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
            partial.deleteRecursively()
            Files.createDirectories(partial)
            extract(tarball, partial)
            if (!Files.isExecutable(partial.resolve(LAUNCHER))) throw IOException("The update has no runnable launcher")
            val target = versions.resolve(version)
            target.deleteRecursively()
            Files.move(partial, target, ATOMIC_MOVE)
            val previous = if (Files.isSymbolicLink(current)) Files.readSymbolicLink(current).fileName else null
            point(target)
            prune(keep = setOfNotNull(target.fileName, previous))
        } finally {
            partial.deleteRecursively()
        }
    }

    /** Refuses entries and symlinks that reach outside [into] before anything is moved into place. */
    private fun extract(tarball: Path, into: Path) {
        val outside = tar("-tzf", tarball.toString()).lines().firstOrNull { it.isNotEmpty() && escapes(Path.of(it)) }
        if (outside != null) throw IOException("The update has an entry outside its folder: $outside")
        tar("-xzf", tarball.toString(), "-C", into.toString())
        val top = into.toAbsolutePath().normalize()
        val stray = Files.walk(top).use { paths ->
            paths.filter { Files.isSymbolicLink(it) && !it.parent.resolve(Files.readSymbolicLink(it)).normalize().startsWith(top) }
                .findFirst().orElse(null)
        }
        if (stray != null) throw IOException("The update links outside its folder: ${top.relativize(stray)}")
    }

    private fun escapes(entry: Path) = entry.isAbsolute || entry.any { it.toString() == ".." }

    private fun tar(vararg args: String): String {
        val tar = ProcessBuilder("tar", *args).redirectErrorStream(true).start()
        val output = tar.inputStream.bufferedReader().readText()
        if (tar.waitFor() != 0) throw IOException(output.trim().lines().last().ifEmpty { "tar could not unpack the update" })
        return output
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
            entries.filter { it.fileName !in keep }.forEach { it.deleteRecursively() }
        }
    }
}
