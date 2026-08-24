package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SystemdRelay
import java.util.concurrent.TimeUnit

/**
 * A running relay, however it was started.
 *
 * The app either spawns sing-box itself or asks systemd to. Under systemd there
 * is no [Process] to hold: the relay runs as another user, so signalling it
 * fails silently and liveness has to be asked of the service manager instead.
 */
internal interface RelayProcess {
    val isAlive: Boolean

    /** Blocks until the relay exits, returning its exit status. */
    fun awaitExit(): Int

    fun stop(timeoutSeconds: Long)
}

internal class OwnedRelayProcess(private val proc: Process) : RelayProcess {
    val pid: Long get() = proc.pid()

    override val isAlive: Boolean get() = proc.isAlive

    override fun awaitExit(): Int = proc.waitFor()

    override fun stop(timeoutSeconds: Long) {
        proc.destroy()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
}

internal class ManagedRelayProcess : RelayProcess {
    override val isAlive: Boolean get() = ManagedRelay.isActive()

    override fun awaitExit(): Int {
        while (ManagedRelay.isActive()) Thread.sleep(EXIT_POLL_MS)
        return ManagedRelay.lastExitStatus()
    }

    override fun stop(timeoutSeconds: Long) {
        ManagedRelay.stop()
    }

    private companion object {
        const val EXIT_POLL_MS = 500L
    }
}

/**
 * Drives the relay unit through `systemctl`.
 *
 * Start and stop go through polkit, which `cli/polkit/10-vpn-chain-systemd.rules`
 * authorizes for an active local session without a password. Without that rule
 * installed these calls fail rather than hang, because a GUI session has no
 * agent attached to this process.
 */
internal object ManagedRelay {
    val available: Boolean get() = SystemdRelay.available

    fun isActive(): Boolean = systemctl("is-active", "--quiet").exitCode == 0

    fun start(): Result = systemctl("start")

    fun stop(): Result = systemctl("stop")

    /**
     * The unit's last main exit status. Reported as a failure when systemd can't
     * answer, so a relay that vanished never reads as a clean shutdown.
     */
    fun lastExitStatus(): Int {
        val result = run("systemctl", "show", "-p", "ExecMainStatus", "--value", SystemdRelay.UNIT)
        return result.output.trim().toIntOrNull() ?: 1
    }

    private fun systemctl(vararg args: String): Result =
        run("systemctl", *args, SystemdRelay.UNIT)

    private fun run(vararg command: String): Result {
        val proc = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return Result(exitCode = -1, output = output)
        }
        return Result(exitCode = proc.exitValue(), output = output)
    }

    data class Result(val exitCode: Int, val output: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    private const val COMMAND_TIMEOUT_S = 15L
}
