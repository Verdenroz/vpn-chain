package com.verdenroz.vpnchain.core.common

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only storage for state that holds keys. The 0700 directory is the boundary: DataStore
 * re-creates its file by rename under the umask, and the startup repair tightens it again.
 * Filesystems without POSIX permissions (Windows) fall back to plain creation.
 */
object PrivateStorage {
    private val systemPosix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val dirPerms = PosixFilePermissions.fromString("rwx------")
    private val filePerms = PosixFilePermissions.fromString("rw-------")
    private val fileAttrs: Array<FileAttribute<*>> =
        if (systemPosix) arrayOf(PosixFilePermissions.asFileAttribute(filePerms)) else emptyArray()

    /** Whether this filesystem has POSIX permissions; without them nothing here can be made owner-only. */
    val posix: Boolean get() = systemPosix

    /** Attributes that create a file owner-only in one step, for channels opened outside this object. */
    val ownerOnlyFile: Array<FileAttribute<*>> get() = fileAttrs

    /** Narrows an existing file, such as a bound socket, to its owner. */
    fun restrict(file: Path) {
        if (systemPosix) Files.setPosixFilePermissions(file, filePerms)
    }

    /** `~/.config/vpn-chain`, created or repaired once per process before anything uses it. */
    val configDir: File by lazy { prepareConfigDir(File(System.getProperty("user.home"))) }

    internal fun prepareConfigDir(
        home: File,
        posix: Boolean = systemPosix,
        setPerms: (Path, Set<PosixFilePermission>) -> Unit = { path, perms -> Files.setPosixFilePermissions(path, perms) },
    ): File {
        val dir = File(home, ".config/vpn-chain")
        dir.parentFile.mkdirs()
        if (!posix) return dir.apply { mkdirs() }
        createOwnerOnly(dir, setPerms)
        dir.listFiles().orEmpty()
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .forEach { tighten(it.toPath(), filePerms, setPerms) }
        return dir
    }

    /** Creates [dir] owner-only, or adopts an existing one. Throws unless the result is our own 0700 directory. */
    fun privateDir(
        dir: File,
        setPerms: (Path, Set<PosixFilePermission>) -> Unit = { path, perms -> Files.setPosixFilePermissions(path, perms) },
    ): File {
        dir.parentFile?.mkdirs()
        if (!systemPosix) return dir.apply { mkdirs() }
        val path = dir.toPath()
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(dirPerms))
        } catch (_: FileAlreadyExistsException) {
            if (!isOwnDirectory(path)) throw IOException("$dir is not a directory owned by this user")
            tighten(path, dirPerms, setPerms)
        }
        if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != dirPerms) {
            throw IOException("$dir is not owner-only")
        }
        return dir
    }

    /** Creates [file] owner-only. Anything already there that is not our own regular file is removed first. */
    fun ensurePrivateFile(file: File) {
        val path = file.toPath()
        try {
            Files.createFile(path, *fileAttrs)
        } catch (_: FileAlreadyExistsException) {
            if (!systemPosix) return
            if (isOwnRegularFile(path)) {
                tighten(path, filePerms) { p, perms -> Files.setPosixFilePermissions(p, perms) }
            } else {
                Files.delete(path)
                Files.createFile(path, *fileAttrs)
            }
        }
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

    private val currentUser by lazy {
        val probe = Files.createTempFile("vpn-chain-owner", null)
        try {
            Files.getOwner(probe)
        } finally {
            Files.delete(probe)
        }
    }

    private fun isOwnDirectory(path: Path) =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == currentUser

    private fun isOwnRegularFile(path: Path) =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == currentUser

    private fun createOwnerOnly(dir: File, setPerms: (Path, Set<PosixFilePermission>) -> Unit) {
        try {
            Files.createDirectory(dir.toPath(), PosixFilePermissions.asFileAttribute(dirPerms))
        } catch (_: FileAlreadyExistsException) {
            tighten(dir.toPath(), dirPerms, setPerms)
        }
    }

    // An entry owned by another user (left by a sudo run) cannot be chmodded; skip it rather than block startup.
    private fun tighten(path: Path, perms: Set<PosixFilePermission>, setPerms: (Path, Set<PosixFilePermission>) -> Unit) {
        try {
            setPerms(path, perms)
        } catch (_: IOException) {
        }
    }
}
