package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.common.PrivateStorage
import com.verdenroz.vpnchain.core.logging.Logger
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val posix: Boolean = PrivateStorage.posix,
    private val accept: (ServerSocketChannel) -> SocketChannel = ServerSocketChannel::accept,
) {

    /** @return the serving job, or null where the socket cannot be made owner-only. */
    fun start(lock: EngineLock): Job? {
        if (!posix) {
            logger.w(TAG, "no POSIX permissions on this filesystem; not serving ${paths.socket}")
            return null
        }
        PrivateStorage.privateDir(paths.runtimeDir.toFile())
        Files.deleteIfExists(paths.socket)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(paths.socket))
        PrivateStorage.restrict(paths.socket)
        logger.i(TAG, "control socket at ${paths.socket}")
        return scope.launch(Dispatchers.IO) {
            lock.use { server.use { acceptLoop(server) } }
        }
    }

    // A failed accept (fd exhaustion, say) is retried: only closing the socket ends serving.
    private suspend fun CoroutineScope.acceptLoop(server: ServerSocketChannel) {
        var backoff = ACCEPT_RETRY_MIN_MS
        while (isActive) {
            val client = try {
                runInterruptible { accept(server) }
            } catch (_: ClosedChannelException) {
                return
            } catch (e: IOException) {
                logger.w(TAG, "accept failed, retrying in ${backoff}ms: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(ACCEPT_RETRY_MAX_MS)
                continue
            }
            backoff = ACCEPT_RETRY_MIN_MS
            launch { serve(client) }
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
        val failure = try {
            handler.handle(message.request).collect { lines.send(message.id, it) }
            return
        } catch (e: IOException) {
            logger.d(TAG, "client dropped: ${e.message}")
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A request that dies unanswered leaves a blocking client waiting forever.
            logger.e(TAG, "request ${message.request} failed", e)
            Reply.Failure(FailureCode.Failed, e.message ?: e::class.simpleName.orEmpty())
        }
        try {
            lines.send(message.id, failure)
        } catch (e: IOException) {
            logger.d(TAG, "client left before its failure reply: ${e.message}")
        }
    }

    private suspend fun LineChannel.receive(): ClientMessage? = try {
        runInterruptible { readLine() }?.let { engineJson.decodeFromString<ClientMessage>(it) }
    } catch (e: LineTooLongException) {
        logger.w(TAG, "dropping a client: ${e.message}")
        send(0, Reply.Failure(FailureCode.BadRequest, e.message.orEmpty()))
        null
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
        const val ACCEPT_RETRY_MIN_MS = 100L
        const val ACCEPT_RETRY_MAX_MS = 5_000L
    }
}
