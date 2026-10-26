package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.LogRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

internal class FakeChainRepository : ChainRepository {
    override val status = MutableStateFlow(ChainStatus())
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected: Flow<Boolean> = flowOf(false)
    var connects = 0

    override suspend fun connect(): Result<Unit> {
        connects++
        connectionIntent.value = true
        status.update { it.copy(state = TunnelState.Connected) }
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connectionIntent.value = false
        status.update { it.copy(state = TunnelState.Disconnected) }
    }

    override suspend fun reconnect() = connect()
    override fun openSystemVpnSettings() = Unit
    override suspend fun release() = Unit
}

internal class FakeProfileRepository(saved: List<SavedProfile>) : ProfileRepository {
    override val profiles = MutableStateFlow(saved)
    override val activeProfileId = MutableStateFlow(saved.firstOrNull()?.id)
    override val profile: Flow<SavedProfile?> =
        combine(profiles, activeProfileId) { all, id -> all.firstOrNull { it.id == id } }

    override suspend fun setActive(id: String) {
        activeProfileId.value = id
    }

    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = TODO()
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = TODO()
    override suspend fun rename(id: String, name: String) = TODO()
    override suspend fun delete(id: String) = TODO()
    override suspend fun clear() = TODO()
    override suspend fun importFromSecretsEnv(text: String, activate: Boolean) = TODO()
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean) = TODO()
}

internal class FakeLogRepository : LogRepository {
    private val buffer = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = buffer

    fun append(message: String) = buffer.update { it + LogEntry(it.size.toLong(), message) }

    override fun clear() = buffer.update { emptyList() }
}

internal object SilentLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

internal fun relayProfile(id: String, name: String) = SavedProfile(
    id = id,
    name = name,
    relay = ChainProfile(
        vpsIp = "203.0.113.10",
        vlessUuid = "00000000-0000-4000-8000-000000000000",
        realityPublicKey = "test-reality-key",
        shortId = "abcd",
    ),
)

internal fun handlerOver(
    chain: ChainRepository,
    profiles: ProfileRepository,
    logs: LogRepository,
    settings: Flow<UserSettings> = MutableStateFlow(UserSettings()),
) = EngineHandler(
    chain = chain,
    profiles = profiles,
    logs = logs,
    connect = ConnectChainUseCase(chain),
    disconnect = DisconnectChainUseCase(chain),
    switchProfile = SwitchProfileUseCase(profiles, chain),
    settings = settings,
)
