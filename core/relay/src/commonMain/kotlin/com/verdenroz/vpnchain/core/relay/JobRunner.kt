package com.verdenroz.vpnchain.core.relay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Starts [request] and follows it to a terminal state; see [awaitJob]. */
suspend fun RelayAgent.runJob(
    request: JobRequest,
    reconnect: suspend () -> Unit,
    poll: Duration = 2.seconds,
    timeout: Duration = 10.minutes,
): AgentJob = awaitJob(startJob(request), reconnect, poll, timeout)

/**
 * Polls job [id] until it is terminal. A job that restarts xray drops the tunnel, so a lost
 * connection calls [reconnect] and polling resumes. The runner rolls the change back unless it is
 * confirmed in time, so a job awaiting confirmation is confirmed as soon as the relay answers.
 */
suspend fun RelayAgent.awaitJob(
    id: String,
    reconnect: suspend () -> Unit,
    poll: Duration = 2.seconds,
    timeout: Duration = 10.minutes,
): AgentJob = withTimeout(timeout) { follow(id, reconnect, poll) }

private suspend fun RelayAgent.follow(id: String, reconnect: suspend () -> Unit, poll: Duration): AgentJob {
    while (true) {
        val job = try {
            job(id).also { if (it.state == JobState.AwaitingConfirm) confirm(id) }
        } catch (_: AgentUnreachable) {
            reconnect()
            null
        }
        if (job != null && job.state.isTerminal) return job
        delay(poll)
    }
}
