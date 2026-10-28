package com.verdenroz.vpnchain.core.engine

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SingleInstanceTest {
    private val root: Path = Files.createTempDirectory("single-instance")
    private val paths = EnginePaths(runtimeDir = root.resolve("run/vpn-chain"), configDir = root.resolve("config"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shows = AtomicInteger()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        root.deleteRecursively()
    }

    private fun serve(lock: EngineLock) {
        val handler = handlerOver(FakeChainRepository(), FakeProfileRepository(emptyList()), FakeLogRepository(), onShow = shows::incrementAndGet)
        ControlServer(handler, paths, "1.2.3", scope, SilentLogger).start(lock)
    }

    @Test
    fun `a second launch asks the holder to show and takes no lock`() {
        val first = claimOrHandOff(paths, "1.2.3", SilentLogger)
        assertNotNull(first)
        serve(first)

        assertNull(claimOrHandOff(paths, "1.2.3", SilentLogger))
        assertNull(claimOrHandOff(paths, "1.2.3", SilentLogger))
        assertEquals(2, shows.get())
    }

    @Test
    fun `a launch that races the holder's socket bind still reaches it`() {
        val first = claimOrHandOff(paths, "1.2.3", SilentLogger)!!
        thread {
            Thread.sleep(300)
            serve(first)
        }

        assertNull(claimOrHandOff(paths, "1.2.3", SilentLogger))
        assertEquals(1, shows.get())
    }

    @Test
    fun `a holder that never serves leaves the launch with an error`() {
        EngineLock.tryAcquire(paths.lock)!!.use {
            assertFailsWith<EngineUnavailableException> {
                claimOrHandOff(paths, "1.2.3", SilentLogger, patience = 200.milliseconds)
            }
        }
    }

    @Test
    fun `a dead holder's lock and socket file are taken over`() {
        Files.createDirectories(paths.runtimeDir)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(paths.socket)) }
        assertEquals(true, Files.exists(paths.socket))
        EngineLock.tryAcquire(paths.lock)!!.close()

        val lock = claimOrHandOff(paths, "1.2.3", SilentLogger)
        assertNotNull(lock)
        serve(lock)

        assertNull(claimOrHandOff(paths, "1.2.3", SilentLogger))
        assertEquals(1, shows.get())
    }
}
