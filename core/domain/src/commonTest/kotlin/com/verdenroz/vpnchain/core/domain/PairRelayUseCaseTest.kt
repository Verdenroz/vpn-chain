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
import com.verdenroz.vpnchain.core.relay.AgentIdentity
import com.verdenroz.vpnchain.core.relay.AgentJob
import com.verdenroz.vpnchain.core.relay.AgentUnreachable
import com.verdenroz.vpnchain.core.relay.DeviceRole
import com.verdenroz.vpnchain.core.relay.Enrolled
import com.verdenroz.vpnchain.core.relay.ExitChange
import com.verdenroz.vpnchain.core.relay.JobRequest
import com.verdenroz.vpnchain.core.relay.ManagementKey
import com.verdenroz.vpnchain.core.relay.PairingException
import com.verdenroz.vpnchain.core.relay.RelayAgent
import com.verdenroz.vpnchain.core.relay.id
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEY = object : ManagementKey {
    override val publicKey = ByteArray(65) { it.toByte() }
    override suspend fun sign(message: ByteArray) = ByteArray(0)
}

private const val LINK =
    "vpnchain://pair?v=1&h=203.0.113.10&p=443&sni=www.example.com&pbk=pubKeyPUBKEYpubKeyPUBKEYpubKeyPUBKEYpubKeyP" +
        "&sid=ab12&id=11111111-1111-4111-8111-111111111111&n=laptop&t=tokenTOKENtokenTOKENtokenTOKENtokenTOKENtok&e=2000"

private const val CONF = "[Interface]\nPrivateKey = FAKEKEY"
private const val BAD_CONF = "not a conf"

private val ROTATED = AgentIdentity("laptop", "22222222-2222-4222-8222-222222222222", "cd34", 443, listOf("www.example.com"))

class PairRelayUseCaseTest {

    private val log = mutableListOf<String>()
    private val profiles = PairingProfiles(log)
    private val steps = mutableListOf<PairingStep>()

    private fun useCase(
        role: DeviceRole = DeviceRole.Admin,
        enroll: () -> Enrolled = { Enrolled(ROTATED, KEY.id, role) },
        state: TunnelState = TunnelState.Disconnected,
        connect: Result<Unit> = Result.success(Unit),
        restart: Result<Unit> = Result.success(Unit),
        now: Long = 1_000,
        chain: PairingChain = PairingChain(state, log, connect, restart),
    ) = PairRelayUseCase(profiles, chain, PairingAgent(enroll), KEY) { now }

    private fun failure(result: Result<PairedRelay>) = assertIs<PairingFailed>(result.exceptionOrNull())

    @Test
    fun `a link connects, enrolls and reconnects with the rotated identity`() = runTest {
        val result = useCase().pair(LINK) { step, _ -> steps += step }

        assertEquals(PairedRelay("203.0.113.10", admin = true), result.getOrNull())
        assertEquals(listOf(PairingStep.Connecting, PairingStep.Registering, PairingStep.Reconnecting), steps)
        assertEquals(listOf("add:203.0.113.10", "connect", "save", "restart"), log)
        val relay = profiles.profile.value?.relay
        assertEquals(ROTATED.uuid, relay?.vlessUuid)
        assertEquals("cd34", relay?.shortId)
        assertEquals(KEY.id, relay?.managementKeyId)
        assertEquals(true, relay?.managementAdmin)
        assertEquals("www.example.com", relay?.sni)
    }

    @Test
    fun `an entry hop in the reply is saved on the new connection before the reconnect`() = runTest {
        val result = useCase(enroll = { Enrolled(ROTATED, KEY.id, DeviceRole.Admin, entryHop = CONF) }).pair(LINK) { _, _ -> }

        assertEquals(PairedRelay("203.0.113.10", admin = true, entryHopFailed = false), result.getOrNull())
        assertEquals(listOf("add:203.0.113.10", "connect", "save", "entry", "restart"), log)
        assertEquals("203.0.113.9", profiles.profile.value?.wireGuard?.endpointHost)
        assertEquals(ROTATED.uuid, profiles.profile.value?.relay?.vlessUuid)
    }

    @Test
    fun `no entry hop in the reply leaves the connection without one`() = runTest {
        val result = useCase().pair(LINK) { _, _ -> }

        assertEquals(false, result.getOrNull()?.entryHopFailed)
        assertNull(profiles.profile.value?.wireGuard)
    }

    @Test
    fun `an entry hop that does not parse still finishes pairing and says so`() = runTest {
        val result = useCase(enroll = { Enrolled(ROTATED, KEY.id, DeviceRole.Admin, entryHop = BAD_CONF) }).pair(LINK) { _, _ -> }

        assertEquals(PairedRelay("203.0.113.10", admin = true, entryHopFailed = true), result.getOrNull())
        assertEquals(listOf("add:203.0.113.10", "connect", "save", "restart"), log)
        assertEquals(KEY.id, profiles.profile.value?.relay?.managementKeyId)
        assertNull(profiles.profile.value?.wireGuard)
    }

    @Test
    fun `enrolling waits for the tunnel to be connected after connect returns`() = runTest {
        val chain = PairingChain(TunnelState.Disconnected, log, Result.success(Unit), Result.success(Unit), connectsAtOnce = false)
        val enroll = {
            if (chain.status.value.state != TunnelState.Connected) throw AgentUnreachable("The tunnel is not connected.")
            Enrolled(ROTATED, KEY.id, DeviceRole.Admin)
        }
        val pairing = async(start = CoroutineStart.UNDISPATCHED) { useCase(enroll = enroll, chain = chain).pair(LINK) { _, _ -> } }
        runCurrent()
        assertEquals(listOf("add:203.0.113.10", "connect"), log)

        chain.status.value = ChainStatus(state = TunnelState.Connected)

        assertEquals(PairedRelay("203.0.113.10", admin = true), pairing.await().getOrNull())
    }

    @Test
    fun `a tunnel that settles in error leaves nothing behind`() = runTest {
        val chain = PairingChain(TunnelState.Disconnected, log, Result.success(Unit), Result.success(Unit), connectsAtOnce = false)
        val pairing = async(start = CoroutineStart.UNDISPATCHED) { useCase(chain = chain).pair(LINK) { _, _ -> } }
        runCurrent()

        chain.status.value = ChainStatus(state = TunnelState.Error)

        val error = failure(pairing.await())
        assertEquals(PairingFailed.Reason.Unreachable, error.reason)
        assertNull(profiles.profile.value)
    }

    @Test
    fun `a live tunnel is moved onto the new connection instead of started`() = runTest {
        useCase(state = TunnelState.Connected).pair(LINK) { _, _ -> }

        assertEquals(listOf("add:203.0.113.10", "restart", "save", "restart"), log)
    }

    @Test
    fun `an expired link is refused before anything is saved or dialled`() = runTest {
        val error = failure(useCase(now = 2_000).pair(LINK) { _, _ -> })

        assertEquals(PairingFailed.Reason.Expired, error.reason)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a relay that cannot be reached leaves nothing behind`() = runTest {
        val error = failure(useCase(connect = Result.failure(IllegalStateException("no"))).pair(LINK) { _, _ -> })

        assertEquals(PairingStep.Connecting, error.step)
        assertEquals(PairingFailed.Reason.Unreachable, error.reason)
        assertNull(profiles.profile.value)
        assertEquals(listOf("add:203.0.113.10", "connect", "delete", "disconnect"), log)
    }

    @Test
    fun `a member device is saved and reported as no admin`() = runTest {
        val result = useCase(role = DeviceRole.Member).pair(LINK) { _, _ -> }

        assertEquals(PairedRelay("203.0.113.10", admin = false), result.getOrNull())
        assertEquals(false, profiles.profile.value?.relay?.managementAdmin)
        assertEquals("laptop", profiles.profile.value?.relay?.managementDevice)
    }

    @Test
    fun `a used token and an expired token read differently`() = runTest {
        val used = failure(useCase(enroll = { throw AgentException(401, "unauthorized") }).pair(LINK) { _, _ -> })
        val expired = failure(useCase(enroll = { throw AgentException(410, "expired") }).pair(LINK) { _, _ -> })

        assertEquals(PairingFailed.Reason.Used, used.reason)
        assertEquals(PairingFailed.Reason.Expired, expired.reason)
        assertEquals(PairingStep.Registering, used.step)
    }

    @Test
    fun `a failed first admin code is marked so the app can say where to get another`() = runTest {
        val first = "$LINK&f=1"
        val used = failure(useCase(enroll = { throw AgentException(401, "unauthorized") }).pair(first) { _, _ -> })
        val expired = failure(useCase(now = 2_000).pair(first) { _, _ -> })
        val other = failure(useCase(enroll = { throw AgentException(401, "unauthorized") }).pair(LINK) { _, _ -> })

        assertEquals(listOf(true, true, false), listOf(used.firstAdmin, expired.firstAdmin, other.firstAdmin))
    }

    @Test
    fun `a failed enroll puts back the connection that was active and its tunnel`() = runTest {
        profiles.seed("home")
        val error = failure(useCase(state = TunnelState.Connected, enroll = { throw AgentUnreachable("down") }).pair(LINK) { _, _ -> })

        assertEquals(PairingFailed.Reason.Unreachable, error.reason)
        assertEquals("home", profiles.activeProfileId.value)
        assertEquals(listOf("add:203.0.113.10", "restart", "delete", "setActive:home", "restart"), log)
    }

    @Test
    fun `a reconnect that fails keeps the enrolled connection`() = runTest {
        val error = failure(useCase(restart = Result.failure(IllegalStateException("no"))).pair(LINK) { _, _ -> })

        assertEquals(PairingStep.Reconnecting, error.step)
        assertEquals(KEY.id, profiles.profile.value?.relay?.managementKeyId)
    }

    @Test
    fun `text that is not a link is refused without touching anything`() = runTest {
        val result = useCase().pair("VPS_IP=203.0.113.10") { _, _ -> }

        assertIs<PairingException>(result.exceptionOrNull())
        assertEquals(emptyList(), log)
    }

    @Test
    fun `only a pairing link is accepted`() {
        assertTrue(useCase().accepts(LINK))
        assertTrue(!useCase().accepts("VPS_IP=203.0.113.10"))
    }

    @Test
    fun `an unexpected exception is wrapped so its text never reaches the screen`() = runTest {
        val error = failure(useCase(enroll = { throw IllegalStateException("JSON input: 22222222-2222") }).pair(LINK) { _, _ -> })

        assertEquals(PairingFailed.Reason.Failed, error.reason)
        assertTrue("22222222" !in error.message.orEmpty())
    }

    @Test
    fun `no failure message shows the link`() = runTest {
        val error = failure(useCase(enroll = { throw AgentException(401, "unauthorized") }).pair(LINK) { _, _ -> })

        val shown = error.toString() + error.message + error.cause
        for (secret in listOf("tokenTOKEN", "11111111-1111", "ab12", "pubKeyPUBKEY")) {
            assertTrue(secret !in shown, secret)
        }
    }
}

private class PairingAgent(private val enroll: () -> Enrolled) : RelayAgent {
    override suspend fun enroll(token: String, managementKey: ManagementKey) = enroll()
    override suspend fun status() = error("unused")
    override suspend fun devices(): List<AgentDevice> = error("unused")
    override suspend fun add(name: String, role: DeviceRole) = error("unused")
    override suspend fun rename(name: String, to: String) = error("unused")
    override suspend fun revoke(name: String) = error("unused")
    override suspend fun rekey(name: String) = error("unused")
    override suspend fun pair(name: String) = error("unused")
    override suspend fun refillPool() = error("unused")
    override suspend fun exit() = error("unused")
    override suspend fun setExit(change: ExitChange) = error("unused")
    override suspend fun startJob(request: JobRequest) = error("unused")
    override suspend fun job(id: String): AgentJob = error("unused")
    override suspend fun confirm(id: String) = error("unused")
}

private class PairingProfiles(private val log: MutableList<String>) : ProfileRepository {
    override val profiles = MutableStateFlow<List<SavedProfile>>(emptyList())
    override val activeProfileId = MutableStateFlow<String?>(null)
    override val profile = MutableStateFlow<SavedProfile?>(null)

    fun seed(id: String) {
        profiles.value = listOf(SavedProfile(id, id, relay = ChainProfile("203.0.113.1", "u", "k", "ab")))
        activeProfileId.value = id
        publish()
    }

    private fun publish() {
        profile.value = profiles.value.firstOrNull { it.id == activeProfileId.value }
    }

    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?): String {
        log += "add:$name"
        profiles.value += SavedProfile("new", name, relay, wireGuard)
        activeProfileId.value = "new"
        publish()
        return "new"
    }

    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) {
        log += "save"
        profiles.value = profiles.value.map { if (it.id == activeProfileId.value) it.copy(relay = relay) else it }
        publish()
    }

    override suspend fun delete(id: String) {
        log += "delete"
        profiles.value = profiles.value.filterNot { it.id == id }
        if (activeProfileId.value == id) activeProfileId.value = profiles.value.firstOrNull()?.id
        publish()
    }

    override suspend fun setActive(id: String) {
        log += "setActive:$id"
        activeProfileId.value = id
        publish()
    }

    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean): Result<WireGuardEntry> {
        if (text == BAD_CONF) return Result.failure(IllegalArgumentException("Bad PrivateKey."))
        log += "entry"
        val entry = WireGuardEntry(privateKey = "k", address = "10.8.0.2/32", peerPublicKey = "p", endpointHost = "203.0.113.9")
        profiles.value = profiles.value.map { if (it.id == activeProfileId.value) it.copy(wireGuard = entry) else it }
        publish()
        return Result.success(entry)
    }
}

private class PairingChain(
    state: TunnelState,
    private val log: MutableList<String>,
    private val connect: Result<Unit>,
    private val restart: Result<Unit>,
    private val connectsAtOnce: Boolean = true,
) : ChainRepository {
    override val status = MutableStateFlow(ChainStatus(state = state))
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    override suspend fun connect(): Result<Unit> {
        log += "connect"
        return connect.also { settle(it) }
    }
    override suspend fun reconnect() = Result.success(Unit)
    override suspend fun restart(): Result<Unit> {
        log += "restart"
        return restart.also { settle(it) }
    }

    private fun settle(result: Result<Unit>) {
        status.value = ChainStatus(
            state = when {
                result.isFailure -> TunnelState.Error
                connectsAtOnce -> TunnelState.Connected
                else -> TunnelState.Connecting
            },
        )
    }
    override suspend fun disconnect() {
        log += "disconnect"
    }
    override suspend fun release() = Unit
}
