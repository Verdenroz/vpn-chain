package com.verdenroz.vpnchain.core.relay

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private fun job(state: JobState) =
    AgentJob(id = "a".repeat(32), kind = "restart-xray", state = state, queuedAt = 1, updatedAt = 2)

private class ScriptedAgent(private val script: ArrayDeque<Any>) : RelayAgent {
    val calls = mutableListOf<String>()

    override suspend fun job(id: String): AgentJob {
        calls += "job"
        return when (val next = script.removeFirst()) {
            is AgentJob -> next
            else -> throw next as Throwable
        }
    }

    override suspend fun confirm(id: String) {
        calls += "confirm"
    }

    override suspend fun startJob(request: JobRequest): String {
        calls += "start"
        return "a".repeat(32)
    }

    override suspend fun status() = error("unused")
    override suspend fun devices() = error("unused")
    override suspend fun add(name: String, role: DeviceRole) = error("unused")
    override suspend fun rename(name: String, to: String) = error("unused")
    override suspend fun revoke(name: String) = error("unused")
    override suspend fun rekey(name: String) = error("unused")
    override suspend fun pair(name: String) = error("unused")
    override suspend fun refillPool() = error("unused")
    override suspend fun exit() = error("unused")
    override suspend fun setExit(change: ExitChange) = error("unused")
    override suspend fun enroll(token: String, managementKey: ManagementKey) = error("unused")
}

class JobRunnerTest {

    @Test
    fun `a restart job is followed through the dropped tunnel to its confirmation`() = runTest {
        val agent = ScriptedAgent(
            ArrayDeque(
                listOf(
                    job(JobState.Queued),
                    job(JobState.Running),
                    AgentUnreachable("down"),
                    AgentUnreachable("down"),
                    job(JobState.AwaitingConfirm),
                    job(JobState.Confirmed),
                ),
            ),
        )
        var reconnects = 0

        val done = agent.runJob(JobRequest.RestartXray, reconnect = { reconnects++ }, poll = 1.seconds)

        assertEquals(JobState.Confirmed, done.state)
        assertEquals(2, reconnects)
        assertEquals(listOf("start", "job", "job", "job", "job", "job", "confirm", "job"), agent.calls)
    }

    @Test
    fun `a rolled back job ends the wait without a confirm`() = runTest {
        val agent = ScriptedAgent(ArrayDeque(listOf(job(JobState.Running), job(JobState.RolledBack))))

        val done = agent.awaitJob("a".repeat(32), reconnect = {}, poll = 1.seconds)

        assertEquals(JobState.RolledBack, done.state)
        assertEquals(listOf("job", "job"), agent.calls)
    }
}
