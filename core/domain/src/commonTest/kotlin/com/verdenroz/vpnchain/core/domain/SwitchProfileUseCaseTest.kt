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
import kotlin.test.assertTrue

class SwitchProfileUseCaseTest {

    @Test
    fun `a live tunnel is taken down before the new profile is dialled`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Connected, log)

        val result = world.switchProfile("b")

        assertTrue(result.isSuccess)
        assertEquals(listOf("setActive:b", "disconnect", "connect"), log)
    }

    @Test
    fun `a stopped tunnel stays stopped`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Disconnected, log)

        world.switchProfile("b")

        assertEquals(listOf("setActive:b"), log)
    }

    @Test
    fun `choosing the active profile does nothing`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Connected, log)

        world.switchProfile("a")

        assertEquals(emptyList(), log)
    }

    @Test
    fun `a connect in flight is restarted onto the new profile`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Connecting, log)

        world.switchProfile("b")

        assertEquals(listOf("setActive:b", "disconnect", "connect"), log)
    }

    @Test
    fun `a dropped tunnel the user still wants is restarted onto the new profile`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Error, log, wanted = true)

        world.switchProfile("b")

        assertEquals(listOf("setActive:b", "disconnect", "connect"), log)
    }

    @Test
    fun `a failed reconnect is reported`() = runTest {
        val log = mutableListOf<String>()
        val world = SwitchWorld(TunnelState.Connected, log, connectResult = Result.failure(IllegalStateException("no")))

        assertTrue(world.switchProfile("b").isFailure)
    }
}

private class SwitchWorld(
    state: TunnelState,
    log: MutableList<String>,
    connectResult: Result<Unit> = Result.success(Unit),
    wanted: Boolean = false,
) {
    val switchProfile =
        SwitchProfileUseCase(RecordingProfiles(log), RecordingChain(state, log, connectResult, wanted))
}

private class RecordingProfiles(private val log: MutableList<String>) : ProfileRepository {
    override val profile: Flow<SavedProfile?> = MutableStateFlow(null)
    override val profiles: Flow<List<SavedProfile>> = MutableStateFlow(emptyList())
    override val activeProfileId: Flow<String?> = MutableStateFlow("a")
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = Unit
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = "id"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) {
        log += "setActive:$id"
    }
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean): Result<WireGuardEntry> {
        log += "import:$intoActive:$activate"
        return Result.success(WireGuardEntry("k", "10.0.0.2/32", "p", "198.51.100.7"))
    }
}

private class RecordingChain(
    state: TunnelState,
    private val log: MutableList<String>,
    private val connectResult: Result<Unit>,
    wanted: Boolean,
) : ChainRepository {
    override val status: Flow<ChainStatus> = MutableStateFlow(ChainStatus(state = state))
    override val connectionIntent = MutableStateFlow(wanted)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    override suspend fun connect(): Result<Unit> {
        log += "connect"
        return connectResult
    }
    override suspend fun reconnect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {
        log += "disconnect"
    }
    override suspend fun release() = Unit
}

class ImportWireGuardConfUseCaseTest {

    @Test
    fun `importing while connected restarts onto the imported connection`() = runTest {
        val log = mutableListOf<String>()
        val import = ImportWireGuardConfUseCase(RecordingProfiles(log), RecordingChain(TunnelState.Connected, log, Result.success(Unit), false))

        assertTrue(import("conf").isSuccess)

        assertEquals(listOf("import:false:true", "disconnect", "connect"), log)
    }

    @Test
    fun `importing while stopped leaves the tunnel alone`() = runTest {
        val log = mutableListOf<String>()
        val import = ImportWireGuardConfUseCase(RecordingProfiles(log), RecordingChain(TunnelState.Disconnected, log, Result.success(Unit), false))

        import("conf", intoActive = true)

        assertEquals(listOf("import:true:true"), log)
    }

    @Test
    fun `a failed restart is reported apart from a successful import`() = runTest {
        val log = mutableListOf<String>()
        val chain = RecordingChain(TunnelState.Connected, log, Result.failure(IllegalStateException("no")), false)

        val imported = ImportWireGuardConfUseCase(RecordingProfiles(log), chain)("conf").getOrThrow()

        assertTrue(imported.reconnect.isFailure)
    }

    @Test
    fun `saving a conf neither activates it nor touches a live tunnel`() = runTest {
        val log = mutableListOf<String>()
        val import = ImportWireGuardConfUseCase(RecordingProfiles(log), RecordingChain(TunnelState.Connected, log, Result.success(Unit), false))

        assertTrue(import.save("conf").isSuccess)

        assertEquals(listOf("import:false:false"), log)
    }
}
