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

private const val HELPER_USAGE_EXIT = 2

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
    markArgs: List<String> = emptyList(),
    onMarkRefused: suspend () -> Unit = {},
    run: HelperRunner,
): KillSwitchEngagement {
    if (exemptIps.isEmpty()) return KillSwitchEngagement(KillSwitchState.NoExemptAddresses, engaged = false)
    val localArgs = if (allowLocal) listOf("--allow-local") else emptyList()
    var outcome = run(listOf("up") + localArgs + markArgs + exemptIps)
    // A helper built before --allow-mark rejects it as a usage error (2); a bad value is 3. Keep the table, without the hole.
    if (markArgs.isNotEmpty() && (outcome as? HelperOutcome.Exited)?.code == HELPER_USAGE_EXIT) {
        onMarkRefused()
        outcome = run(listOf("up") + localArgs + exemptIps)
    }
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
 * [engaged] is whether the old session's table is believed loaded: it skips a
 * pointless `down`, and stands in for `status` when the helper cannot answer.
 * @return the engagement, or null when no table is left.
 */
internal suspend fun rearmForRestart(
    exemptIps: List<String>,
    allowLocal: Boolean,
    wanted: Boolean,
    engaged: Boolean,
    markArgs: List<String> = emptyList(),
    onMarkRefused: suspend () -> Unit = {},
    run: HelperRunner,
): KillSwitchEngagement? {
    if (!wanted) {
        if (engaged) run(listOf("down"))
        return null
    }
    val outcome = engageKillSwitch(exemptIps, allowLocal, markArgs, onMarkRefused, run)
    if (outcome.engaged || outcome.fault !in UNASKED_FAULTS) return outcome
    return outcome.copy(engaged = tableLoaded(run, assumed = engaged))
}

/** Faults [engageKillSwitch] reports without asking `status`; on a switch the old table may still be loaded. */
private val UNASKED_FAULTS = setOf(KillSwitchState.NoExemptAddresses, KillSwitchState.HelperNotFound)

private suspend fun tableLoaded(run: HelperRunner, assumed: Boolean): Boolean {
    val status = run(listOf("status")) as? HelperOutcome.Exited ?: return assumed
    return when (status.code) {
        0 -> true
        1 -> false
        else -> assumed
    }
}

/**
 * What one connect attempt does with the kill switch. [armedBySwitch] is set
 * when a switch's re-arm already loaded this config's table, so launching
 * does not load it again. [holdOnFailure] is set when a table already guards
 * a session the user wants protected: a failed attempt leaves it in place.
 */
internal data class AttemptArming(val armedBySwitch: Boolean, val holdOnFailure: Boolean) {
    fun engagesOnLaunch(wanted: Boolean): Boolean = wanted && !armedBySwitch

    fun liftsOnFailure(): Boolean = !holdOnFailure

    companion object {
        fun forStart(engaged: Boolean, enabled: Boolean) =
            AttemptArming(armedBySwitch = false, holdOnFailure = engaged && enabled)

        fun forSwitch(rearm: KillSwitchEngagement?, enabled: Boolean) = AttemptArming(
            armedBySwitch = rearm?.engaged == true && rearm.fault == null,
            holdOnFailure = rearm?.engaged == true && enabled,
        )
    }
}
