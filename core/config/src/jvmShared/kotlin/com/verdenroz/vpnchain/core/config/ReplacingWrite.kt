package com.verdenroz.vpnchain.core.config

import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Replaces [target] with [text] in one rename. The text goes to a fresh,
 * uniquely named file beside it, created exclusively and without following
 * links, so nothing planted in the directory at either name is written through.
 * [mode] is a POSIX mode string such as `rw-r-----`, ignored where there is none.
 */
fun writeReplacing(target: Path, text: String, mode: String) {
    val staged = target.resolveSibling("${target.fileName}.${randomHexToken(STAGED_NAME_BYTES)}.tmp")
    val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    val perms = PosixFilePermissions.fromString(mode)
    val attrs: Array<FileAttribute<*>> =
        if (posix) arrayOf(PosixFilePermissions.asFileAttribute(perms)) else emptyArray()
    try {
        Files.newByteChannel(staged, setOf(CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS), *attrs).use { channel ->
            val bytes = ByteBuffer.wrap(text.encodeToByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
        }
        // The creation mode passes through the umask; this sets it exactly, by descriptor, not by link.
        if (posix) {
            Files.getFileAttributeView(staged, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .setPermissions(perms)
        }
        Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staged)
    }
}

private const val STAGED_NAME_BYTES = 8
