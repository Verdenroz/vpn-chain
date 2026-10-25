package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.logging.Logger
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.SerializationException

/**
 * Serves [EngineHandler] on the control socket. [start] needs the [EngineLock]
 * and holds it until the server stops: only the lock holder may replace the
 * socket file.
 */
class ControlServer(
    private val handler: EngineHandler,
    private val paths: EnginePaths,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {

    fun start(lock: EngineLock): Job {
        OwnerOnly.dir(paths.runtimeDir)
        Files.deleteIfExists(paths.socket)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(paths.socket))
        OwnerOnly.restrict(paths.socket)
        logger.i(TAG, "control socket at ${paths.socket}")
        return scope.launch(Dispatchers.IO) {
            lock.use {
                server.use {
                    try {
                        while (isActive) {
                            val client = runInterruptible { server.accept() }
                            launch { serve(client) }
                        }
                    } catch (e: IOException) {
                        logger.e(TAG, "control socket stopped accepting", e)
                    }
                }
            }
        }
    }

    private suspend fun serve(client: SocketChannel) {
        try {
            LineChannel(client).use { converse(it) }
        } catch (e: IOException) {
            logger.d(TAG, "client dropped: ${e.message}")
        }
    }

    // A client that hangs up or garbles a line ends its subscriptions, never a connect or disconnect underway.
    private suspend fun converse(lines: LineChannel) = supervisorScope {
        val hello = lines.receive() ?: return@supervisorScope
        val request = hello.request
        if (request !is Request.Hello) {
            lines.send(hello.id, Reply.Failure(FailureCode.BadRequest, "the first request must be a hello"))
            return@supervisorScope
        }
        if (request.version != PROTOCOL_VERSION) {
            val message = "this engine ($appVersion) speaks protocol $PROTOCOL_VERSION, the client ${request.version}"
            lines.send(hello.id, Reply.Failure(FailureCode.Version, message))
            return@supervisorScope
        }
        lines.send(hello.id, Reply.Hello(PROTOCOL_VERSION, appVersion))
        val subscriptions = mutableListOf<Job>()
        while (true) {
            val message = lines.receive() ?: break
            val answer = launch { answer(lines, message) }
            if (message.request is Request.Subscribe) subscriptions += answer
        }
        subscriptions.forEach(Job::cancel)
    }

    private suspend fun answer(lines: LineChannel, message: ClientMessage) {
        try {
            handler.handle(message.request).collect { lines.send(message.id, it) }
        } catch (e: IOException) {
            logger.d(TAG, "client dropped: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A request that dies unanswered leaves a blocking client waiting forever.
            logger.e(TAG, "request ${message.request} failed", e)
            lines.send(message.id, Reply.Failure(FailureCode.Failed, e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private suspend fun LineChannel.receive(): ClientMessage? = try {
        runInterruptible { readLine() }?.let { engineJson.decodeFromString<ClientMessage>(it) }
    } catch (e: IOException) {
        logger.d(TAG, "client dropped: ${e.message}")
        null
    } catch (e: SerializationException) {
        logger.w(TAG, "malformed request: ${e.message}")
        null
    }

    private fun LineChannel.send(id: Long, reply: Reply) =
        writeLine(engineJson.encodeToString(ServerMessage(id, reply)))

    private companion object {
        const val TAG = "ControlServer"
    }
}
