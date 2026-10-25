package com.verdenroz.vpnchain.core.common

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Owner-only storage for state that holds keys, applied at creation so no file is ever briefly readable. */
object PrivateStorage {
    private val dirPerms = PosixFilePermissions.fromString("rwx------")
    private val filePerms = PosixFilePermissions.fromString("rw-------")
    private val dirAttr = PosixFilePermissions.asFileAttribute(dirPerms)
    private val fileAttr = PosixFilePermissions.asFileAttribute(filePerms)

    private val knownFiles = listOf("vpnchain.preferences_pb", "singbox-cache.db", "secrets.env", "release.jks")

    /** Creates `~/.config/vpn-chain` owner-only, or tightens an existing one and the files in it. */
    fun configDir(home: File = File(System.getProperty("user.home"))): File {
        val dir = File(home, ".config/vpn-chain")
        dir.parentFile.mkdirs()
        if (dir.exists()) {
            Files.setPosixFilePermissions(dir.toPath(), dirPerms)
        } else {
            Files.createDirectory(dir.toPath(), dirAttr)
        }
        knownFiles.map { File(dir, it) }.filter { it.exists() }.forEach {
            Files.setPosixFilePermissions(it.toPath(), filePerms)
        }
        return dir
    }

    fun createFile(file: File) {
        if (!file.exists()) Files.createFile(file.toPath(), fileAttr)
    }
}
