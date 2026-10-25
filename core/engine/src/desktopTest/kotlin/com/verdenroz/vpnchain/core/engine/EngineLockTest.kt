package com.verdenroz.vpnchain.core.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EngineLockTest {
    private val root: Path = Files.createTempDirectory("engine-lock")
    private val lockFile = root.resolve("config/engine.lock")

    @AfterTest
    fun tearDown() {
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        root.deleteRecursively()
    }

    @Test
    fun `a second acquire in the same process is refused`() {
        EngineLock.tryAcquire(lockFile)!!.use {
            assertNull(EngineLock.tryAcquire(lockFile))
        }
    }

    @Test
    fun `the lock is free again once released`() {
        EngineLock.tryAcquire(lockFile)!!.close()
        assertNotNull(EngineLock.tryAcquire(lockFile)).close()
    }

    @Test
    fun `another process holding the lock wins`() {
        val java = ProcessHandle.current().info().command().get()
        val holder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LockHolder::class.java.name, lockFile.toString())
            .redirectErrorStream(true)
            .start()
        try {
            assertEquals("held", holder.inputReader().readLine())
            assertNull(EngineLock.tryAcquire(lockFile))
        } finally {
            holder.destroy()
            holder.waitFor(10, TimeUnit.SECONDS)
        }
        assertNotNull(EngineLock.tryAcquire(lockFile)).close()
    }
}

object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = EngineLock.tryAcquire(Path.of(args[0]))
        println(if (lock != null) "held" else "busy")
        Thread.sleep(Long.MAX_VALUE)
    }
}
