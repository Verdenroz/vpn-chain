package com.verdenroz.vpnchain.core.engine

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Newline-framed UTF-8 over a blocking socket channel. Reads and writes go
 * straight to the channel: `Channels.newInputStream` holds the channel's
 * blocking lock while it waits, which would stall a concurrent write.
 */
internal class LineChannel(private val channel: SocketChannel) : Closeable {
    private var buffer = ByteBuffer.allocate(4096)
    private var scanned = 0
    private val writeLock = Any()

    /** @return the next line without its newline, or null once the peer has closed. */
    fun readLine(): String? {
        while (true) {
            for (i in scanned until buffer.position()) {
                if (buffer.get(i) == NEWLINE) {
                    val line = String(buffer.array(), 0, i, Charsets.UTF_8)
                    buffer.flip().position(i + 1)
                    buffer.compact()
                    scanned = 0
                    return line
                }
            }
            scanned = buffer.position()
            if (!buffer.hasRemaining()) buffer = ByteBuffer.allocate(buffer.capacity() * 2).put(buffer.flip())
            if (channel.read(buffer) < 0) return null
        }
    }

    fun writeLine(line: String) {
        val bytes = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        synchronized(writeLock) {
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }

    override fun close() = channel.close()

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
