package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.model.KillSwitchState
import java.util.concurrent.TimeUnit

sealed interface HelperOutcome {
    data class Exited(val code: Int, val output: String) : HelperOutcome
    data object NotFound : HelperOutcome
    data object TimedOut : HelperOutcome
}

typealias HelperRunner = suspend (args: List<String>) -> HelperOutcome

fun processHelperRunner(bin: String, timeoutSeconds: Long = 5): HelperRunner = { args ->
    runCatching {
        val proc = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
        if (proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            HelperOutcome.Exited(proc.exitValue(), proc.inputStream.bufferedReader().readText())
        } else {
            proc.destroyForcibly()
            HelperOutcome.TimedOut
        }
    }.getOrDefault(HelperOutcome.NotFound)
}

private val DENIED = listOf("could not acquire CAP_NET_ADMIN", "Operation not permitted", "Permission denied")

/** Null when the table was installed, otherwise why the chain is left unprotected. */
internal suspend fun engageKillSwitchFault(
    exemptIps: List<String>,
    allowLocal: Boolean,
    run: HelperRunner,
): KillSwitchState? {
    if (exemptIps.isEmpty()) return KillSwitchState.NoExemptAddresses
    val localArgs = if (allowLocal) listOf("--allow-local") else emptyList()
    return when (val outcome = run(listOf("up") + localArgs + exemptIps)) {
        is HelperOutcome.Exited -> when {
            outcome.code == 0 -> null
            DENIED.any { outcome.output.contains(it, ignoreCase = true) } -> KillSwitchState.HelperUnprivileged
            else -> KillSwitchState.HelperFailed
        }
        HelperOutcome.NotFound -> KillSwitchState.HelperNotFound
        HelperOutcome.TimedOut -> KillSwitchState.HelperTimedOut
    }
}
