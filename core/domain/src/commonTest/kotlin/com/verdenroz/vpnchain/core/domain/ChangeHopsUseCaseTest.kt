package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChangeHopsUseCaseTest {

    @Test
    fun `a conf is reviewed without saving anything`() = runTest {
        val world = HopsWorld()

        val reviewed = world.useCase.reviewDeviceExit(conf(PRIVATE_A))

        assertEquals("203.0.113.9", reviewed.getOrThrow().endpointHost)
        assertEquals(emptyList(), world.log)
    }

    @Test
    fun `an unparseable conf is refused`() = runTest {
        assertTrue(HopsWorld().useCase.reviewEntryHop("[Interface]").isFailure)
    }

    @Test
    fun `an exit cannot reuse the entry hop's key`() = runTest {
        val world = HopsWorld(entry = entry(PRIVATE_A))

        val error = world.useCase.reviewDeviceExit(conf(PRIVATE_A)).exceptionOrNull()

        assertEquals(Hop.Entry, assertIs<SameKeyAsOtherHop>(error).other)
    }

    @Test
    fun `an entry hop cannot reuse the exit's key`() = runTest {
        val world = HopsWorld(exit = entry(PRIVATE_A))

        val error = world.useCase.reviewEntryHop(conf(PRIVATE_A)).exceptionOrNull()

        assertEquals(Hop.Exit, assertIs<SameKeyAsOtherHop>(error).other)
    }

    @Test
    fun `a different key passes review`() = runTest {
        val world = HopsWorld(entry = entry(PRIVATE_A))

        assertTrue(world.useCase.reviewDeviceExit(conf(PRIVATE_B)).isSuccess)
    }

    @Test
    fun `setting the exit while connected restarts onto it`() = runTest {
        val world = HopsWorld(state = TunnelState.Connected)

        assertTrue(world.useCase.setDeviceExit(entry(PRIVATE_A)).isSuccess)

        assertEquals(listOf("exit:set", "restart"), world.log)
    }

    @Test
    fun `setting the entry hop while stopped leaves the tunnel alone`() = runTest {
        val world = HopsWorld(state = TunnelState.Disconnected)

        world.useCase.setEntryHop(entry(PRIVATE_A))

        assertEquals(listOf("entry:set"), world.log)
    }

    @Test
    fun `a failed reconnect is not a failed save`() = runTest {
        val world = HopsWorld(state = TunnelState.Connected, restart = Result.failure(IllegalStateException("no")))

        val saved = world.useCase.setDeviceExit(null).getOrThrow()

        assertTrue(saved.reconnect.isFailure)
        assertEquals(listOf("exit:clear", "restart"), world.log)
    }

    @Test
    fun `a refused write restarts nothing`() = runTest {
        val world = HopsWorld(state = TunnelState.Connected, accepts = false)

        assertTrue(world.useCase.setDeviceExit(entry(PRIVATE_A)).isFailure)
        assertEquals(emptyList(), world.log)
    }
}

private const val PRIVATE_A = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
private const val PRIVATE_B = "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI="

private fun entry(key: String) = WireGuardEntry(key, "10.2.0.2/32", "peer", "198.51.100.7")

private fun conf(key: String) = """
    [Interface]
    PrivateKey = $key
    Address = 10.2.0.2/32

    [Peer]
    PublicKey = cHVibGljIGtleSBwbGFjZWhvbGRlciBmb3IgdGVzdHM=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent()

private class HopsWorld(
    entry: WireGuardEntry? = null,
    exit: WireGuardEntry? = null,
    state: TunnelState = TunnelState.Disconnected,
    restart: Result<Unit> = Result.success(Unit),
    accepts: Boolean = true,
) {
    val log = mutableListOf<String>()
    val useCase = ChangeHopsUseCase(HopsProfiles(entry, exit, accepts, log), HopsChain(state, restart, log))
}

private class HopsProfiles(
    entry: WireGuardEntry?,
    exit: WireGuardEntry?,
    private val accepts: Boolean,
    private val log: MutableList<String>,
) : ProfileRepository {
    private val relay = ChainProfile(vpsIp = "198.51.100.1", vlessUuid = "u", realityPublicKey = "k", shortId = "ab")
    override val profile: Flow<SavedProfile?> =
        MutableStateFlow(SavedProfile(id = "a", name = "a", relay = relay, wireGuard = entry, deviceExit = exit))
    override val profiles: Flow<List<SavedProfile>> = MutableStateFlow(emptyList())
    override val activeProfileId: Flow<String?> = MutableStateFlow("a")
    override suspend fun saveRelay(relay: ChainProfile) = Unit
    override suspend fun setEntryHop(entry: WireGuardEntry?): Result<Unit> = write("entry", entry)
    override suspend fun setDeviceExit(exit: WireGuardEntry?): Result<Unit> = write("exit", exit)
    private fun write(hop: String, value: WireGuardEntry?): Result<Unit> {
        if (!accepts) return Result.failure(IllegalStateException("refused"))
        log += "$hop:${if (value == null) "clear" else "set"}"
        return Result.success(Unit)
    }
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = Unit
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = "id"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean) =
        Result.failure<WireGuardEntry>(IllegalStateException())
}

private class HopsChain(
    state: TunnelState,
    private val restartResult: Result<Unit>,
    private val log: MutableList<String>,
) : ChainRepository {
    override val status: Flow<ChainStatus> = MutableStateFlow(ChainStatus(state = state))
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun reconnect(): Result<Unit> = Result.success(Unit)
    override suspend fun restart(): Result<Unit> {
        log += "restart"
        return restartResult
    }
    override suspend fun disconnect() = Unit
    override suspend fun release() = Unit
}
