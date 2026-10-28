package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.core.relay.AgentDevice
import com.verdenroz.vpnchain.core.relay.AgentException
import com.verdenroz.vpnchain.core.relay.AgentExit
import com.verdenroz.vpnchain.core.relay.AgentIdentity
import com.verdenroz.vpnchain.core.relay.AgentJob
import com.verdenroz.vpnchain.core.relay.AgentUnreachable
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.Enrolled
import com.verdenroz.vpnchain.core.relay.ExitChange
import com.verdenroz.vpnchain.core.relay.JobRequest
import com.verdenroz.vpnchain.core.relay.JobState
import com.verdenroz.vpnchain.core.relay.ManagementKey
import com.verdenroz.vpnchain.core.relay.PairingCode
import com.verdenroz.vpnchain.core.relay.Refilled
import com.verdenroz.vpnchain.core.relay.RelayAgent
import com.verdenroz.vpnchain.core.relay.RelayStatus
import com.verdenroz.vpnchain.core.relay.Revoked
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val LAPTOP = AgentDevice("laptop", DeviceRole.Admin, added = "2026-10-01T00:00:00Z", up = 1, down = 2)
private val PHONE = AgentDevice("phone", DeviceRole.Member, added = "2026-10-02T00:00:00Z", up = 0, down = 0)
private val CODE = PairingCode("vpnchain://pair?v=1&t=tokenvalue", expires = 2_000)
private const val OWN_KEY = "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM="
private const val OTHER_KEY = "mQxH1s9tYw2aC4eG6iK8mO0qS3uW5yA7cE9gI1kM3oQ="

private fun conf(privateKey: String) = """
    [Interface]
    PrivateKey = $privateKey
    Address = 10.8.0.2/32

    [Peer]
    PublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent()
private const val JOB = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"

class ManageDevicesUseCaseTest {

    private val log = mutableListOf<String>()
    private val profiles = DevicesProfiles(keyed = true)
    private val chain = DevicesChain(log)
    private val agent = DevicesAgent(log)

    private fun useCase() = ManageDevicesUseCase(profiles, chain, agent)

    private fun reason(result: Result<*>) = assertIs<DevicesFailed>(result.exceptionOrNull()).reason

    @Test
    fun `an admin sees every device and whether the pool is empty`() = runTest {
        agent.devices = listOf(LAPTOP, PHONE)
        agent.pool = 0

        val board = useCase().load().getOrThrow()

        assertEquals(DeviceBoard(listOf(LAPTOP, PHONE), self = "laptop", admin = true, poolEmpty = true), board)
    }

    @Test
    fun `a member sees only its own device and is no admin`() = runTest {
        profiles.set(admin = false, device = "phone")
        agent.devices = listOf(PHONE)

        val board = useCase().load().getOrThrow()

        assertEquals(DeviceBoard(listOf(PHONE), self = "phone", admin = false, poolEmpty = false), board)
    }

    @Test
    fun `a connection without a management key asks the relay nothing`() = runTest {
        profiles.set(keyed = false)

        assertEquals(DevicesFailed.Reason.NoKey, reason(useCase().load()))
        assertEquals(emptyList(), log)
    }

    @Test
    fun `an unreachable relay reads as offline`() = runTest {
        agent.unreachable = true

        assertEquals(DevicesFailed.Reason.Offline, reason(useCase().load()))
    }

    @Test
    fun `adding a device adds it and then issues its code`() = runTest {
        val code = useCase().add("phone").getOrThrow()

        assertEquals(CODE, code)
        assertEquals(listOf("status", "add:phone", "pair:phone"), log)
    }

    @Test
    fun `an entry hop is handed to the relay with the pairing request`() = runTest {
        useCase().add("phone", conf(OTHER_KEY)).getOrThrow()

        assertEquals(listOf("status", "add:phone", "pair:phone:entry"), log)
    }

    @Test
    fun `a different entry hop passes the check`() = runTest {
        profiles.ownEntryHop(OWN_KEY)

        useCase().checkEntryHop(conf(OTHER_KEY)).getOrThrow()
    }

    @Test
    fun `an entry hop that does not parse is refused with the parser's words`() = runTest {
        val failure = assertIs<DevicesFailed>(useCase().checkEntryHop("[Interface]\nPrivateKey = $OTHER_KEY").exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Refused, failure.reason)
        assertEquals("Config has no peer.", failure.message)
    }

    @Test
    fun `this device's own entry hop is refused without echoing its key`() = runTest {
        profiles.ownEntryHop(OWN_KEY)

        val failure = assertIs<DevicesFailed>(useCase().checkEntryHop(conf(OWN_KEY)).exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Refused, failure.reason)
        assertTrue(OWN_KEY !in failure.message.orEmpty())
        assertEquals(emptyList(), log)
    }

    @Test
    fun `adding with an empty pool changes nothing`() = runTest {
        agent.pool = 0

        assertEquals(DevicesFailed.Reason.PoolEmpty, reason(useCase().add("phone")))
        assertEquals(listOf("status"), log)
    }

    @Test
    fun `a name that is taken reads as taken and issues no code`() = runTest {
        agent.conflict = true

        assertEquals(DevicesFailed.Reason.NameTaken, reason(useCase().add("phone")))
        assertEquals(DevicesFailed.Reason.NameTaken, reason(useCase().rename("phone", "laptop")))
        assertTrue("pair:phone" !in log)
    }

    @Test
    fun `cutting a device off that needs a restart follows the job to its confirmation`() = runTest {
        agent.revokeJob = JOB
        agent.jobs = ArrayDeque(listOf(job(JobState.Running), job(JobState.AwaitingConfirm), job(JobState.Confirmed)))

        useCase().cutOff("phone").getOrThrow()

        assertEquals(listOf("revoke:phone", "job", "job", "confirm", "job"), log)
    }

    @Test
    fun `a job the runner rolls back is a failure`() = runTest {
        agent.revokeJob = JOB
        agent.jobs = ArrayDeque(listOf(job(JobState.RolledBack, reason = "the relay did not answer")))

        val failed = assertIs<DevicesFailed>(useCase().cutOff("phone").exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Failed, failed.reason)
        assertEquals("the relay did not answer", failed.message)
    }

    @Test
    fun `refilling the pool restarts through a job and reconnects when the tunnel drops`() = runTest {
        agent.refillJob = JOB
        agent.jobs = ArrayDeque(listOf(job(JobState.Running), job(JobState.Confirmed)))
        agent.dropOnce = true

        useCase().refillPool().getOrThrow()

        assertEquals(listOf("refill", "job", "restart", "job", "job"), log.filter { it != "confirm" })
    }

    @Test
    fun `an admin is told the exit and a member is not asked`() = runTest {
        agent.exit = AgentExit.WireGuard("203.0.113.9:51820")

        val admin = useCase().loadExit().getOrThrow()
        profiles.set(admin = false, device = "phone")
        log.clear()
        val member = useCase().loadExit().getOrThrow()

        assertEquals(ExitBoard(admin = true, exit = AgentExit.WireGuard("203.0.113.9:51820")), admin)
        assertEquals(ExitBoard(admin = false, exit = null), member)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `changing the exit follows the restart job through a reconnect to its confirmation`() = runTest {
        agent.exitJob = JOB
        agent.jobs = ArrayDeque(listOf(job(JobState.Running), job(JobState.Confirmed)))
        agent.dropOnce = true

        useCase().setExit(ExitChange.Direct).getOrThrow()

        assertEquals(listOf("set-exit:Direct", "job", "restart", "job", "job"), log.filter { it != "confirm" })
    }

    @Test
    fun `an exit change the runner rolls back is a failure`() = runTest {
        agent.exitJob = JOB
        agent.jobs = ArrayDeque(listOf(job(JobState.RolledBack, reason = "not confirmed within the window")))

        val failed = assertIs<DevicesFailed>(useCase().setExit(ExitChange.WireGuard("conf")).exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Failed, failed.reason)
    }

    @Test
    fun `an exit the relay refuses reads as refused in the relay's words`() = runTest {
        agent.refuse = AgentException(400, "no endpoint in the WireGuard file")

        val failed = assertIs<DevicesFailed>(useCase().loadExit().exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Refused, failed.reason)
        assertEquals("no endpoint in the WireGuard file", failed.message)
    }

    @Test
    fun `a new code asks the agent to pair the device again`() = runTest {
        assertEquals(CODE, useCase().newCode("phone").getOrThrow())
        assertEquals(listOf("pair:phone"), log)
    }

    @Test
    fun `a refusal keeps the relay's own words`() = runTest {
        agent.refuse = AgentException(403, "this key's role may not do that")

        val failed = assertIs<DevicesFailed>(useCase().newCode("phone").exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Refused, failed.reason)
        assertEquals("this key's role may not do that", failed.message)
    }

    @Test
    fun `an unexpected exception is wrapped so its text never reaches the screen`() = runTest {
        agent.refuse = IllegalStateException("tokenvalue")

        val failed = assertIs<DevicesFailed>(useCase().newCode("phone").exceptionOrNull())

        assertEquals(DevicesFailed.Reason.Failed, failed.reason)
        assertTrue("tokenvalue" !in failed.message.orEmpty())
    }

    @Test
    fun `changes fire when the key or reachability changes and not otherwise`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<Unit>()
        val collecting = launch { useCase().changes.toList(seen) }

        chain.status.value = ChainStatus(state = TunnelState.Connected)
        chain.status.value = ChainStatus(state = TunnelState.Connected, exitIp = "203.0.113.9")
        profiles.set(keyed = false)
        collecting.cancel()

        assertEquals(3, seen.size)
    }
}

private fun job(state: JobState, reason: String? = null) =
    AgentJob(id = JOB, kind = "restart-xray", state = state, reason = reason, queuedAt = 1, updatedAt = 2)

private class DevicesAgent(private val log: MutableList<String>) : RelayAgent {
    var devices = listOf(LAPTOP)
    var pool = 3
    var conflict = false
    var unreachable = false
    var refuse: Throwable? = null
    var revokeJob: String? = null
    var refillJob: String? = null
    var exitJob: String? = null
    var exit: AgentExit = AgentExit.Direct
    var jobs = ArrayDeque<AgentJob>()
    var dropOnce = false

    private fun gate() {
        if (unreachable) throw AgentUnreachable("down")
        refuse?.let { throw it }
    }

    override suspend fun status(): RelayStatus {
        log += "status"
        gate()
        return RelayStatus("0.1.0", "26.3.27", devices.size, pool, jobs = true)
    }

    override suspend fun devices(): List<AgentDevice> {
        gate()
        return devices
    }

    override suspend fun add(name: String, role: DeviceRole): AgentIdentity {
        log += "add:$name"
        if (conflict) throw AgentException(409, "device $name already exists")
        return AgentIdentity(name, "u", "s", 443, listOf("www.example.com"))
    }

    override suspend fun rename(name: String, to: String) {
        if (conflict) throw AgentException(409, "device $to already exists")
        log += "rename:$name:$to"
    }

    override suspend fun revoke(name: String): Revoked {
        log += "revoke:$name"
        return Revoked(countersLost = false, job = revokeJob)
    }

    override suspend fun pair(name: String, entryHop: String?): PairingCode {
        log += "pair:$name" + if (entryHop != null) ":entry" else ""
        gate()
        return CODE
    }

    override suspend fun refillPool(): Refilled {
        log += "refill"
        return Refilled(added = 3, job = refillJob)
    }

    override suspend fun exit(): AgentExit {
        log += "exit"
        gate()
        return exit
    }

    override suspend fun setExit(change: ExitChange): String? {
        log += "set-exit:${change::class.simpleName}"
        return exitJob
    }

    override suspend fun job(id: String): AgentJob {
        log += "job"
        if (dropOnce) {
            dropOnce = false
            throw AgentUnreachable("down")
        }
        return jobs.removeFirst()
    }

    override suspend fun confirm(id: String) {
        log += "confirm"
    }

    override suspend fun rekey(name: String) = error("unused")
    override suspend fun startJob(request: JobRequest) = error("unused")
    override suspend fun enroll(token: String, managementKey: ManagementKey): Enrolled = error("unused")
}

private class DevicesProfiles(keyed: Boolean) : ProfileRepository {
    override val profiles = MutableStateFlow<List<SavedProfile>>(emptyList())
    override val activeProfileId = MutableStateFlow<String?>("a")
    override val profile = MutableStateFlow<SavedProfile?>(null)

    init {
        set(keyed = keyed)
    }

    fun set(keyed: Boolean = true, admin: Boolean = true, device: String = "laptop") {
        val relay = ChainProfile(
            vpsIp = "203.0.113.10",
            vlessUuid = "u",
            realityPublicKey = "k",
            shortId = "ab",
            managementKeyId = "0011223344556677".takeIf { keyed },
            managementAdmin = admin,
            managementDevice = device.takeIf { keyed },
        )
        profile.value = SavedProfile("a", "home", relay = relay)
    }

    fun ownEntryHop(privateKey: String) {
        profile.value = profile.value?.copy(
            wireGuard = WireGuardEntry(privateKey = privateKey, address = "10.2.0.2/32", peerPublicKey = "p", endpointHost = "203.0.113.8"),
        )
    }

    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = error("unused")
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = error("unused")
    override suspend fun delete(id: String) = error("unused")
    override suspend fun setActive(id: String) = error("unused")
    override suspend fun rename(id: String, name: String) = error("unused")
    override suspend fun clear() = error("unused")
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = error("unused")
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean) = error("unused")
}

private class DevicesChain(private val log: MutableList<String>) : ChainRepository {
    override val status = MutableStateFlow(ChainStatus(state = TunnelState.Disconnected))
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    override suspend fun connect() = Result.success(Unit)
    override suspend fun reconnect() = Result.success(Unit)
    override suspend fun restart(): Result<Unit> {
        log += "restart"
        return Result.success(Unit)
    }
    override suspend fun disconnect() = Unit
    override suspend fun release() = Unit
}
