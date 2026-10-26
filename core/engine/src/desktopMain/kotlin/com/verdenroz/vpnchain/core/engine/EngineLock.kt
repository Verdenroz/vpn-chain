package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Held by the one process that owns DataStore and the tunnel. The OS drops it
 * when that process exits, so a crash never leaves it stale.
 */
class EngineLock private constructor(private val channel: FileChannel) : Closeable {

    override fun close() = channel.close()

    companion object {
        /** @return the lock, or null while another engine holds it. */
        fun tryAcquire(file: Path): EngineLock? {
            Files.createDirectories(file.parent)
            val channel = FileChannel.open(file, setOf(CREATE, WRITE), *PrivateStorage.ownerOnlyFile)
            // The JVM tracks its own locks: a second acquire in one process throws instead of returning null.
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return EngineLock(channel)
        }
    }
}
