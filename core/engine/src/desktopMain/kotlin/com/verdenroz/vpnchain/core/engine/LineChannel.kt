package com.verdenroz.vpnchain.core.engine

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Newline-framed UTF-8 over a blocking socket channel. Reads and writes go
 * straight to the channel: `Channels.newInputStream` holds the channel's
 * blocking lock while it waits, which would stall a concurrent write.
 */
/** A peer sent more than [LineChannel.MAX_LINE_BYTES] without a newline. */
internal class LineTooLongException : IOException("a line ran past ${LineChannel.MAX_LINE_BYTES} bytes")

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
            if (!buffer.hasRemaining()) {
                if (buffer.capacity() >= MAX_LINE_BYTES) throw LineTooLongException()
                buffer = ByteBuffer.allocate((buffer.capacity() * 2).coerceAtMost(MAX_LINE_BYTES)).put(buffer.flip())
            }
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

    companion object {
        /** Far above any message: the whole 500-line log buffer is a few hundred KiB. */
        const val MAX_LINE_BYTES = 1 shl 20
        private const val NEWLINE = '\n'.code.toByte()
    }
}
