package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow

class ControlSocketTest {
    private val root: Path = Files.createTempDirectory("engine")
    private val paths = EnginePaths(runtimeDir = root.resolve("run/vpn-chain"), configDir = root.resolve("config"))
    private val uncaught = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } },
    )
    private val chain = FakeChainRepository()
    private val profiles = FakeProfileRepository(listOf(relayProfile("home", "Home"), relayProfile("work", "Work")))
    private val logs = FakeLogRepository()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        root.deleteRecursively()
    }

    private fun serve(
        handler: EngineHandler = handlerOver(chain, profiles, logs),
        posix: Boolean = true,
        accept: (ServerSocketChannel) -> SocketChannel = ServerSocketChannel::accept,
        logger: Logger = SilentLogger,
    ) = ControlServer(handler, paths, "1.2.3", scope, logger, posix, accept).start(EngineLock.tryAcquire(paths.lock)!!)

    private fun client() = EngineClient.connect(paths.socket, "1.2.3")

    @Test
    fun `the handshake reports the engine's version`() {
        serve()
        client().use { assertEquals("1.2.3", it.engineVersion) }
    }

    @Test
    fun `status names the active profile without its keys`() {
        serve()
        val reply = client().use { it.call(Request.Status) }

        val status = assertIs<Reply.Status>(reply).status
        assertEquals(TunnelState.Disconnected, status.state)
        assertEquals("Home", status.profile?.name)
        val wire = engineJson.encodeToString<Reply>(reply)
        assertTrue("203.0.113.10" !in wire && "test-reality-key" !in wire, wire)
    }

    @Test
    fun `up with a name switches to that profile and connects`() {
        serve()
        val reply = client().use { it.call(Request.Up("work")) }

        assertEquals(Reply.Ok, reply)
        assertEquals("work", profiles.activeProfileId.value)
        assertEquals(TunnelState.Connected, chain.status.value.state)
    }

    @Test
    fun `up while connected does not dial again`() {
        serve()
        client().use {
            it.call(Request.Up())
            it.call(Request.Up())
        }
        assertEquals(1, chain.connects)
    }

    @Test
    fun `an unknown profile is refused`() {
        serve()
        val reply = client().use { it.call(Request.Use("nowhere")) }
        assertEquals(FailureCode.NotFound, assertIs<Reply.Failure>(reply).code)
    }

    @Test
    fun `down disconnects`() {
        serve()
        client().use {
            it.call(Request.Up())
            assertEquals(Reply.Ok, it.call(Request.Down))
        }
        assertEquals(TunnelState.Disconnected, chain.status.value.state)
    }

    @Test
    fun `logs returns the newest lines up to the limit`() {
        logs.append("one")
        logs.append("two")
        logs.append("three")
        serve()
        val reply = client().use { it.call(Request.Logs(limit = 2)) }
        assertEquals(listOf("two", "three"), assertIs<Reply.Logs>(reply).lines.map(LogLine::message))
    }

    @Test
    fun `a status subscription follows the chain`() {
        serve()
        val seen = mutableListOf<TunnelState>()
        client().use { client ->
            client.subscribe(Topic.Status) { reply ->
                val state = assertIs<Reply.Status>(reply).status.state
                seen += state
                if (state == TunnelState.Disconnected) chain.status.value = chain.status.value.copy(state = TunnelState.Connected)
                state != TunnelState.Connected
            }
        }
        assertEquals(listOf(TunnelState.Disconnected, TunnelState.Connected), seen)
    }

    @Test
    fun `a log subscription sends the buffer, then only new lines`() {
        logs.append("old")
        serve()
        val batches = mutableListOf<List<String>>()
        client().use { client ->
            client.subscribe(Topic.Logs) { reply ->
                batches += assertIs<Reply.Logs>(reply).lines.map(LogLine::message)
                if (batches.size == 1) logs.append("new")
                batches.size < 2
            }
        }
        assertEquals(listOf(listOf("old"), listOf("new")), batches)
    }

    @Test
    fun `the engine refuses a client speaking another protocol`() {
        serve()
        val reply = rawExchange(ClientMessage(1, Request.Hello(PROTOCOL_VERSION + 1)))
        assertEquals(FailureCode.Version, assertIs<Reply.Failure>(reply).code)
    }

    @Test
    fun `a request must follow a hello`() {
        serve()
        val reply = rawExchange(ClientMessage(1, Request.Status))
        assertEquals(FailureCode.BadRequest, assertIs<Reply.Failure>(reply).code)
    }

    @Test
    fun `a garbled line does not cancel a connect underway`() {
        val chain = FakeChainRepository()
        val dial = CompletableDeferred<Unit>()
        val slow = object : ChainRepository by chain {
            override suspend fun connect(): Result<Unit> = dial.await().let { chain.connect() }
        }
        ControlServer(handlerOver(slow, profiles, logs), paths, "1.2.3", scope, SilentLogger)
            .start(EngineLock.tryAcquire(paths.lock)!!)
        val reply = SocketChannel.open(UnixDomainSocketAddress.of(paths.socket)).use { channel ->
            val lines = LineChannel(channel)
            lines.writeLine(engineJson.encodeToString(ClientMessage(1, Request.Hello(PROTOCOL_VERSION))))
            lines.readLine()
            lines.writeLine(engineJson.encodeToString(ClientMessage(2, Request.Up())))
            lines.writeLine("not json")
            dial.complete(Unit)
            engineJson.decodeFromString<ServerMessage>(lines.readLine()!!).reply
        }
        assertEquals(Reply.Ok, reply)
        assertEquals(TunnelState.Connected, chain.status.value.state)
    }

    private fun rawExchange(message: ClientMessage): Reply =
        SocketChannel.open(UnixDomainSocketAddress.of(paths.socket)).use { channel ->
            val lines = LineChannel(channel)
            lines.writeLine(engineJson.encodeToString(message))
            engineJson.decodeFromString<ServerMessage>(lines.readLine()!!).reply
        }

    @Test
    fun `a client refuses an engine speaking another protocol`() {
        Files.createDirectories(paths.runtimeDir)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(paths.socket))
        val fake = thread {
            server.accept().use { channel ->
                val lines = LineChannel(channel)
                val hello = engineJson.decodeFromString<ClientMessage>(lines.readLine()!!)
                lines.writeLine(engineJson.encodeToString(ServerMessage(hello.id, Reply.Hello(PROTOCOL_VERSION + 1, "9.9.9"))))
            }
        }
        val error = assertFailsWith<EngineVersionException> { client() }
        assertTrue("9.9.9" in error.message.orEmpty(), error.message)
        fake.join()
        server.close()
    }

    @Test
    fun `no engine reads as unavailable`() {
        assertFailsWith<EngineUnavailableException> { client() }
    }

    @Test
    fun `the socket and its directory are owner-only`() {
        serve()
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.runtimeDir)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.socket)))
    }

    @Test
    fun `without POSIX permissions the socket is not served`() {
        assertNull(serve(posix = false))
        assertFalse(Files.exists(paths.socket))
    }

    @Test
    fun `failed accepts are retried, not fatal`() {
        val failures = AtomicInteger(2)
        serve(accept = { server -> if (failures.getAndDecrement() > 0) throw IOException("EMFILE") else server.accept() })
        client().use { assertEquals("1.2.3", it.engineVersion) }
    }

    @Test
    fun `a request that fails after its client left is not an uncaught error`() {
        val dial = CompletableDeferred<Unit>()
        val failing = object : ChainRepository by chain {
            override suspend fun connect(): Result<Unit> = dial.await().let { error("dial blew up") }
        }
        val answered = CompletableDeferred<Unit>()
        val logger = object : Logger by SilentLogger {
            override fun d(tag: String, message: String) {
                if ("before its failure reply" in message) answered.complete(Unit)
            }
        }
        serve(handler = handlerOver(failing, profiles, logs), logger = logger)
        SocketChannel.open(UnixDomainSocketAddress.of(paths.socket)).use { channel ->
            val lines = LineChannel(channel)
            lines.writeLine(engineJson.encodeToString(ClientMessage(1, Request.Hello(PROTOCOL_VERSION))))
            lines.readLine()
            lines.writeLine(engineJson.encodeToString(ClientMessage(2, Request.Up())))
        }
        dial.complete(Unit)
        runBlocking { withTimeout(5_000) { answered.await() } }
        assertEquals(emptyList(), synchronized(uncaught) { uncaught.toList() })
    }

    @Test
    fun `an endless line gets one refusal and the connection dropped`() {
        serve()
        val (reply, after) = SocketChannel.open(UnixDomainSocketAddress.of(paths.socket)).use { channel ->
            val lines = LineChannel(channel)
            // Exactly the cap: bytes the engine never reads would turn its close into a reset.
            val endless = ByteBuffer.wrap(ByteArray(LineChannel.MAX_LINE_BYTES) { 'x'.code.toByte() })
            while (endless.hasRemaining()) channel.write(endless)
            engineJson.decodeFromString<ServerMessage>(lines.readLine()!!).reply to lines.readLine()
        }
        assertEquals(FailureCode.BadRequest, assertIs<Reply.Failure>(reply).code)
        assertNull(after)
    }

    @Test
    fun `an error the engine will redial is marked as such`() {
        chain.status.value = chain.status.value.copy(state = TunnelState.Error)
        chain.connectionIntent.value = true
        serve()
        val status = client().use { assertIs<Reply.Status>(it.call(Request.Status)).status }
        assertTrue(status.willRetry)
    }

    @Test
    fun `an error the supervisor gave up on is final`() {
        chain.status.value = chain.status.value.copy(state = TunnelState.Error)
        chain.connectionIntent.value = true
        serve(handler = handlerOver(chain, profiles, logs, cutOff = MutableStateFlow(true)))
        val status = client().use { assertIs<Reply.Status>(it.call(Request.Status)).status }
        assertFalse(status.willRetry)
    }

    @Test
    fun `an error with auto-reconnect off is final`() {
        chain.status.value = chain.status.value.copy(state = TunnelState.Error)
        chain.connectionIntent.value = true
        serve(handler = handlerOver(chain, profiles, logs, MutableStateFlow(UserSettings(autoReconnect = false))))
        val status = client().use { assertIs<Reply.Status>(it.call(Request.Status)).status }
        assertFalse(status.willRetry)
    }

    @Test
    fun `a stale socket file is replaced`() {
        Files.createDirectories(paths.runtimeDir)
        Files.createFile(paths.socket)
        serve()
        client().use { assertEquals("1.2.3", it.engineVersion) }
    }
}
