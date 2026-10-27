package com.verdenroz.vpnchain.core.relay

/**
 * The relay's management API, reached through the running tunnel. Every call but [enroll] is
 * signed with the device's [ManagementKey]; what the relay lets it do depends on that key's role.
 *
 * @throws AgentUnreachable when there is no tunnel or the relay does not answer.
 * @throws AgentException when the relay refuses, with the status that says why.
 */
interface RelayAgent {
    suspend fun status(): RelayStatus

    /** Every device for an admin key, only its own for a member. */
    suspend fun devices(): List<AgentDevice>

    suspend fun add(name: String, role: DeviceRole = DeviceRole.Member): AgentIdentity

    suspend fun rename(name: String, to: String)

    suspend fun revoke(name: String): Revoked

    /** A new UUID and short ID for [name], with no restart. */
    suspend fun rekey(name: String): AgentIdentity

    /** @return the job id. */
    suspend fun startJob(request: JobRequest): String

    suspend fun job(id: String): AgentJob

    /** Tells the runner the relay is still reachable after the job's change, so it keeps it. */
    suspend fun confirm(id: String)

    /** Registers [managementKey] as this device's key, authenticated by the one-time [token] instead of a signature. */
    suspend fun enroll(token: String, managementKey: ManagementKey): Enrolled
}
