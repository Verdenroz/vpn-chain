package com.verdenroz.vpnchain.desktop.cli

import com.verdenroz.vpnchain.core.common.appVersion
import com.verdenroz.vpnchain.core.engine.EngineClient
import com.verdenroz.vpnchain.core.engine.EnginePaths
import com.verdenroz.vpnchain.core.engine.EngineStatus
import com.verdenroz.vpnchain.core.engine.EngineUnavailableException
import com.verdenroz.vpnchain.core.engine.Reply
import com.verdenroz.vpnchain.core.engine.Request
import com.verdenroz.vpnchain.core.engine.Topic
import com.verdenroz.vpnchain.core.engine.controlSocketSupported
import com.verdenroz.vpnchain.core.engine.resolve
import com.verdenroz.vpnchain.core.model.TunnelState
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException

/** Runs one command against the engine. @return the process exit code. */
internal fun runCli(args: List<String>): Int {
    val out = System.out
    val err = System.err
    val invocation = try {
        parse(args)
    } catch (e: UsageException) {
        err.println("vpn-chain: ${e.message}")
        err.println(USAGE)
        return 2
    }
    if (invocation.command == Command.Help || invocation.command == Command.Version) {
        out.println(if (invocation.command == Command.Help) USAGE else appVersion)
        return 0
    }
    if (!controlSocketSupported) {
        err.println("vpn-chain: the command line isn't supported on this platform yet; use vpn-chain-gui")
        return 1
    }
    return try {
        EngineClient.connect(EnginePaths.system().socket, appVersion).use {
            Session(it, invocation.json, out, err).run(invocation.command)
        }
    } catch (e: EngineUnavailableException) {
        err.println("vpn-chain: ${e.message}; open vpn-chain-gui to start it")
        1
    } catch (e: IOException) {
        err.println("vpn-chain: ${e.message}")
        1
    } catch (e: SerializationException) {
        err.println("vpn-chain: unreadable reply from the engine (${e.message}): restart the engine")
        1
    }
}

/**
 * Whether [status] ends an `up` wait: up, stopped, or an error nothing will
 * redial. An error the engine is about to retry is not an answer yet.
 */
internal fun settles(status: EngineStatus): Boolean = when (status.state) {
    TunnelState.Connecting -> false
    TunnelState.Error -> !status.willRetry
    TunnelState.Connected, TunnelState.Disconnected -> true
}

/** Feeds statuses from [subscribe] until one [settles]; null if the feed ends first. */
internal fun awaitSettled(subscribe: (onStatus: (EngineStatus) -> Boolean) -> Unit): EngineStatus? {
    var settled: EngineStatus? = null
    subscribe { status ->
        if (settles(status)) settled = status
        settled == null
    }
    return settled
}

private class Session(
    private val client: EngineClient,
    private val json: Boolean,
    private val out: PrintStream,
    private val err: PrintStream,
) {

    fun run(command: Command): Int = when (command) {
        is Command.Up -> expectOk(client.call(Request.Up(command.profile))) {
            if (command.wait) {
                awaitSettled(command.timeoutSeconds)
            } else {
                printStatus(currentStatus())
                0
            }
        }
        Command.Down -> expectOk(client.call(Request.Down)) {
            if (json) printStatus(currentStatus()) else out.println("disconnected")
            0
        }
        is Command.Status -> if (command.watch) {
            watch()
        } else {
            printStatus(currentStatus())
            0
        }
        Command.List -> when (val reply = client.call(Request.Profiles)) {
            is Reply.Profiles -> {
                out.println(if (json) profilesJson(reply.profiles).toString() else profilesText(reply.profiles))
                0
            }
            else -> fail(reply)
        }
        is Command.Use -> expectOk(client.call(Request.Use(command.profile))) {
            val status = currentStatus()
            if (json) printStatus(status) else out.println("using ${status.profile?.name ?: command.profile}")
            0
        }
        is Command.Logs -> when (val reply = client.call(Request.Logs(command.lines))) {
            is Reply.Logs -> {
                if (reply.lines.isNotEmpty() || json) {
                    out.println(if (json) logsJson(reply.lines).toString() else logsText(reply.lines))
                }
                0
            }
            else -> fail(reply)
        }
        Command.Help, Command.Version -> error("handled before connecting")
    }

    private fun currentStatus(): EngineStatus = statusOf(client.call(Request.Status))

    private fun statusOf(reply: Reply): EngineStatus = when (reply) {
        is Reply.Status -> reply.status
        is Reply.Failure -> throw IOException(reply.message)
        else -> throw IOException("unexpected reply $reply")
    }

    /** Follows the chain until it settles or [timeoutSeconds] pass: 0 for up, 1 otherwise. */
    private fun awaitSettled(timeoutSeconds: Int): Int {
        var last: EngineStatus? = null
        val timedOut = AtomicBoolean(false)
        // The subscription blocks on a read; closing the client is what interrupts it.
        val deadline = thread(isDaemon = true) {
            try {
                Thread.sleep(timeoutSeconds * 1_000L)
                timedOut.set(true)
                client.close()
            } catch (_: InterruptedException) {
            }
        }
        val settled = try {
            awaitSettled { onStatus -> client.subscribe(Topic.Status) { onStatus(statusOf(it).also { status -> last = status }) } }
        } catch (e: IOException) {
            if (!timedOut.get()) throw e
            null
        } finally {
            deadline.interrupt()
        }
        if (settled == null && timedOut.get()) {
            last?.let(::printStatus)
            err.println("vpn-chain: not connected after ${timeoutSeconds}s")
            return 1
        }
        val status = settled ?: throw IOException("the engine closed the connection")
        printStatus(status)
        return if (status.state == TunnelState.Connected) 0 else 1
    }

    private fun watch(): Int {
        var first = true
        client.subscribe(Topic.Status) { reply ->
            if (!json && !first) out.println()
            first = false
            printStatus(statusOf(reply))
            true
        }
        err.println("vpn-chain: the engine stopped")
        return 1
    }

    private fun printStatus(status: EngineStatus) {
        val detail = status.detail?.let { runBlocking { it.resolve() } }
        out.println(if (json) statusJson(status, detail).toString() else statusText(status, detail))
    }

    private inline fun expectOk(reply: Reply, then: () -> Int): Int = if (reply == Reply.Ok) then() else fail(reply)

    private fun fail(reply: Reply): Int {
        err.println("vpn-chain: ${(reply as? Reply.Failure)?.message ?: "unexpected reply $reply"}")
        return 1
    }
}
