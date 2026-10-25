package com.verdenroz.vpnchain.desktop.cli

import com.verdenroz.vpnchain.core.engine.EngineClient
import com.verdenroz.vpnchain.core.engine.EnginePaths
import com.verdenroz.vpnchain.core.engine.EngineStatus
import com.verdenroz.vpnchain.core.engine.EngineUnavailableException
import com.verdenroz.vpnchain.core.engine.Reply
import com.verdenroz.vpnchain.core.engine.Request
import com.verdenroz.vpnchain.core.engine.Topic
import com.verdenroz.vpnchain.core.engine.resolve
import com.verdenroz.vpnchain.core.model.TunnelState
import java.io.IOException
import java.io.PrintStream
import kotlinx.coroutines.runBlocking

private val appVersion: String get() = System.getProperty("vpnchain.version").orEmpty()

/** Runs one command against the engine. @return the process exit code. */
internal fun runCli(
    args: List<String>,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    connect: () -> EngineClient = { EngineClient.connect(EnginePaths.system().socket, appVersion) },
): Int {
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
    return try {
        connect().use { Session(it, invocation.json, out, err).run(invocation.command) }
    } catch (e: EngineUnavailableException) {
        err.println("vpn-chain: ${e.message}; open vpn-chain-gui to start it")
        1
    } catch (e: IOException) {
        err.println("vpn-chain: ${e.message}")
        1
    }
}

private class Session(
    private val client: EngineClient,
    private val json: Boolean,
    private val out: PrintStream,
    private val err: PrintStream,
) {

    fun run(command: Command): Int = when (command) {
        is Command.Up -> expectOk(client.call(Request.Up(command.profile))) {
            if (command.wait) awaitSettled() else printStatus(currentStatus())
        }
        Command.Down -> expectOk(client.call(Request.Down)) {
            if (json) printStatus(currentStatus()) else out.println("disconnected")
            0
        }
        is Command.Status -> if (command.watch) watch() else printStatus(currentStatus())
        Command.List -> when (val reply = client.call(Request.Profiles)) {
            is Reply.Profiles -> {
                out.println(if (json) profilesJson(reply.profiles).toString() else profilesText(reply.profiles))
                0
            }
            else -> fail(reply)
        }
        is Command.Use -> expectOk(client.call(Request.Use(command.profile))) {
            val status = currentStatus()
            if (json) printStatus(status) else out.println("using ${status.profile?.name}")
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

    private fun currentStatus(): EngineStatus = when (val reply = client.call(Request.Status)) {
        is Reply.Status -> reply.status
        else -> throw IOException("unexpected reply $reply")
    }

    /** Follows the chain until it is up or has failed: 0 for up, 1 for failed. */
    private fun awaitSettled(): Int {
        var settled: EngineStatus? = null
        client.subscribe(Topic.Status) { reply ->
            val status = (reply as Reply.Status).status
            val done = status.state == TunnelState.Connected || status.state == TunnelState.Error
            if (done) settled = status
            !done
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
            printStatus((reply as Reply.Status).status)
            true
        }
        err.println("vpn-chain: the engine stopped")
        return 1
    }

    private fun printStatus(status: EngineStatus): Int {
        val detail = runBlocking { status.detail?.resolve() }
        out.println(if (json) statusJson(status, detail).toString() else statusText(status, detail))
        return 0
    }

    private inline fun expectOk(reply: Reply, then: () -> Int): Int = if (reply == Reply.Ok) then() else fail(reply)

    private fun fail(reply: Reply): Int {
        err.println("vpn-chain: ${(reply as? Reply.Failure)?.message ?: "unexpected reply $reply"}")
        return 1
    }
}
