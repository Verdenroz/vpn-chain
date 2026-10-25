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
) {
    val switchProfile = SwitchProfileUseCase(RecordingProfiles(log), RecordingChain(state, log, connectResult))
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
    override suspend fun importFromSecretsEnv(text: String) = Result.failure<ChainProfile>(IllegalStateException())
}

private class RecordingChain(
    state: TunnelState,
    private val log: MutableList<String>,
    private val connectResult: Result<Unit>,
) : ChainRepository {
    override val status: Flow<ChainStatus> = MutableStateFlow(ChainStatus(state = state))
    override val connectionIntent = MutableStateFlow(false)
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
