package com.verdenroz.vpnchain.core.engine

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/** Owner-only paths for the control socket and lock. Filesystems without POSIX permissions (Windows) get plain creation. */
internal object OwnerOnly {
    val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val dirPerms = PosixFilePermissions.fromString("rwx------")
    val filePerms = PosixFilePermissions.fromString("rw-------")
    val fileAttrs: Array<FileAttribute<*>> =
        if (posix) arrayOf(PosixFilePermissions.asFileAttribute(filePerms)) else emptyArray()

    private val currentUser by lazy {
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }

    /** Creates [dir] owner-only, or adopts an existing one. Throws unless the result is our own 0700 directory. */
    fun dir(dir: Path): Path {
        Files.createDirectories(dir.parent)
        if (!posix) return Files.createDirectories(dir)
        try {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(dirPerms))
        } catch (_: FileAlreadyExistsException) {
            val ours = Files.isDirectory(dir, NOFOLLOW_LINKS) && Files.getOwner(dir, NOFOLLOW_LINKS) == currentUser
            if (!ours) throw IOException("$dir is not a directory owned by this user")
            Files.setPosixFilePermissions(dir, dirPerms)
        }
        if (Files.getPosixFilePermissions(dir, NOFOLLOW_LINKS) != dirPerms) throw IOException("$dir is not owner-only")
        return dir
    }

    fun restrict(file: Path) {
        if (posix) Files.setPosixFilePermissions(file, filePerms)
    }
}
