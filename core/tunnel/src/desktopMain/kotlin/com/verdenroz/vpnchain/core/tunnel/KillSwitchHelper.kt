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
            // nft is the helper's child and outlives it, still holding the table half-built.
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            HelperOutcome.TimedOut
        }
    }.getOrDefault(HelperOutcome.NotFound)
}

private val DENIED = listOf("could not acquire CAP_NET_ADMIN", "Operation not permitted", "Permission denied")

/** [fault] is why the chain is left unprotected; [engaged] is whether a table is in place regardless. */
internal data class KillSwitchEngagement(val fault: KillSwitchState?, val engaged: Boolean)

/**
 * The load is atomic, so a failed or timed-out `up` leaves whatever table was
 * there before. [engaged] asks the helper rather than assuming, and nothing is
 * torn down: removing a live table on a failed re-arm would fail open.
 */
internal suspend fun engageKillSwitch(
    exemptIps: List<String>,
    allowLocal: Boolean,
    run: HelperRunner,
): KillSwitchEngagement {
    if (exemptIps.isEmpty()) return KillSwitchEngagement(KillSwitchState.NoExemptAddresses, engaged = false)
    val localArgs = if (allowLocal) listOf("--allow-local") else emptyList()
    val outcome = run(listOf("up") + localArgs + exemptIps)
    val fault = when (outcome) {
        is HelperOutcome.Exited -> when {
            outcome.code == 0 -> null
            DENIED.any { outcome.output.contains(it, ignoreCase = true) } -> KillSwitchState.HelperUnprivileged
            else -> KillSwitchState.HelperFailed
        }
        HelperOutcome.NotFound -> KillSwitchState.HelperNotFound
        HelperOutcome.TimedOut -> KillSwitchState.HelperTimedOut
    }
    val engaged = when {
        fault == null -> true
        outcome == HelperOutcome.NotFound -> false
        else -> (run(listOf("status")) as? HelperOutcome.Exited)?.code == 0
    }
    return KillSwitchEngagement(fault, engaged)
}

/**
 * The kill switch while a live chain moves to a new config. A wanted table is
 * replaced with the new exemptions in one `up`, never taken down first; only
 * when the setting or the new config says no protection is it brought down.
 * @return the engagement, or null when the table was taken down.
 */
internal suspend fun rearmForRestart(
    exemptIps: List<String>,
    allowLocal: Boolean,
    wanted: Boolean,
    run: HelperRunner,
): KillSwitchEngagement? {
    if (!wanted) {
        run(listOf("down"))
        return null
    }
    return engageKillSwitch(exemptIps, allowLocal, run)
}
