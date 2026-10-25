package com.verdenroz.vpnchain.core.common

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only storage for state that holds keys. The 0700 directory is the boundary: DataStore
 * re-creates its file by rename under the umask, and the startup repair tightens it again.
 * Filesystems without POSIX permissions (Windows) fall back to plain creation.
 */
object PrivateStorage {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val dirPerms = PosixFilePermissions.fromString("rwx------")
    private val filePerms = PosixFilePermissions.fromString("rw-------")
    private val fileAttrs: Array<FileAttribute<*>> =
        if (posix) arrayOf(PosixFilePermissions.asFileAttribute(filePerms)) else emptyArray()

    /** `~/.config/vpn-chain`, created or repaired once per process before anything uses it. */
    val configDir: File by lazy { prepareConfigDir(File(System.getProperty("user.home"))) }

    /** Creates the directory owner-only, or tightens an existing one and every regular file in it. */
    internal fun prepareConfigDir(home: File): File {
        val dir = File(home, ".config/vpn-chain")
        dir.parentFile.mkdirs()
        if (!posix) return dir.apply { mkdirs() }
        try {
            Files.createDirectory(dir.toPath(), PosixFilePermissions.asFileAttribute(dirPerms))
        } catch (_: FileAlreadyExistsException) {
            Files.setPosixFilePermissions(dir.toPath(), dirPerms)
        }
        dir.listFiles().orEmpty()
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .forEach { Files.setPosixFilePermissions(it.toPath(), filePerms) }
        return dir
    }

    /** Created owner-only in one step, in [dir] or the system temp directory when null. */
    fun createTempFile(dir: File?, prefix: String, suffix: String): File {
        val path = if (dir == null) {
            Files.createTempFile(prefix, suffix, *fileAttrs)
        } else {
            Files.createTempFile(dir.toPath(), prefix, suffix, *fileAttrs)
        }
        return path.toFile()
    }
}
