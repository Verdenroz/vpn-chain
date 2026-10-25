package com.verdenroz.vpnchain.core.engine

import java.io.Closeable
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** No engine is listening on the control socket. */
class EngineUnavailableException(socket: Path, cause: Throwable) :
    IOException("no vpn-chain engine is running (nothing at $socket)", cause)

/** The engine and this client speak different protocol versions. */
class EngineVersionException(message: String) : IOException(message)

/**
 * A blocking connection to the engine, one request at a time. Construct with
 * [connect], which has already completed the version handshake.
 */
class EngineClient private constructor(private val lines: LineChannel) : Closeable {
    private var nextId = 1L

    /** The app version the engine reported in its handshake. */
    lateinit var engineVersion: String
        private set

    fun call(request: Request): Reply {
        val id = send(request)
        while (true) {
            val message = receive()
            if (message.id == id) return message.reply
        }
    }

    /** Feeds [topic]'s replies to [onReply] until it returns false or the engine hangs up. */
    fun subscribe(topic: Topic, onReply: (Reply) -> Boolean) {
        val id = send(Request.Subscribe(topic))
        while (true) {
            val message = receiveOrNull() ?: return
            if (message.id == id && !onReply(message.reply)) return
        }
    }

    override fun close() = lines.close()

    private fun handshake(appVersion: String) {
        val reply = call(Request.Hello(PROTOCOL_VERSION))
        if (reply is Reply.Hello && reply.version == PROTOCOL_VERSION) {
            engineVersion = reply.appVersion
            return
        }
        val reason = when (reply) {
            is Reply.Hello -> "the running engine (${reply.appVersion}) speaks protocol ${reply.version}, " +
                "this vpn-chain ($appVersion) speaks $PROTOCOL_VERSION"
            is Reply.Failure -> reply.message
            else -> "unexpected handshake reply $reply"
        }
        throw EngineVersionException("$reason: restart the engine")
    }

    private fun send(request: Request): Long {
        val id = nextId++
        lines.writeLine(engineJson.encodeToString(ClientMessage(id, request)))
        return id
    }

    private fun receive(): ServerMessage = receiveOrNull() ?: throw IOException("the engine closed the connection")

    private fun receiveOrNull(): ServerMessage? =
        lines.readLine()?.let { engineJson.decodeFromString<ServerMessage>(it) }

    companion object {
        fun connect(socket: Path, appVersion: String): EngineClient {
            val channel = try {
                SocketChannel.open(UnixDomainSocketAddress.of(socket))
            } catch (e: IOException) {
                throw EngineUnavailableException(socket, e)
            }
            return EngineClient(LineChannel(channel)).apply {
                try {
                    handshake(appVersion)
                } catch (e: Exception) {
                    close()
                    throw e
                }
            }
        }
    }
}
