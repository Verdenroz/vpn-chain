package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.flow.MutableStateFlow

internal val RELAY = ChainProfile(vpsIp = "203.0.113.10", vlessUuid = "uuid", realityPublicKey = "pubkey", shortId = "ab12")

internal class FakeProfiles(private val log: MutableList<String>) : ProfileRepository {
    override val profiles = MutableStateFlow(listOf(SavedProfile("a", "home", relay = RELAY)))
    override val profile = MutableStateFlow<SavedProfile?>(profiles.value.first())
    override val activeProfileId = MutableStateFlow<String?>("a")
    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) = Unit
    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?) = "b"
    override suspend fun rename(id: String, name: String) {
        log += "rename:$id:$name"
    }
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String) = Result.failure<ChainProfile>(IllegalStateException())
    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean): Result<WireGuardEntry> {
        log += "import:$intoActive:$activate"
        return Result.success(WireGuardEntry("k", "10.0.0.2/32", "p", "198.51.100.7"))
    }
}

internal class FakeChain(state: TunnelState, private val log: MutableList<String>) : ChainRepository {
    override val status = MutableStateFlow(ChainStatus(state = state))
    override val connectionIntent = MutableStateFlow(state == TunnelState.Connected)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
    override suspend fun connect(): Result<Unit> {
        log += "connect"
        return Result.success(Unit)
    }
    override suspend fun reconnect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {
        log += "disconnect"
    }
    override suspend fun release() = Unit
}

internal class FakeSettings : SettingsRepository {
    override val settings = MutableStateFlow(UserSettings())
    override val autostartSupported = false
    override suspend fun setAutoStartOnLogin(enabled: Boolean) = Result.success(Unit)
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) = Unit
    override suspend fun setSystemWideTun(enabled: Boolean) = Unit
    override suspend fun setKillSwitchEnabled(enabled: Boolean) = Unit
    override suspend fun setAllowLocalNetworks(enabled: Boolean) = Unit
    override suspend fun setDnsFilter(filter: DnsFilter) = Unit
    override suspend fun setEntryHopEnabled(enabled: Boolean) = Unit
    override suspend fun setWarpMode(mode: WarpMode) = Unit
    override suspend fun setWarpDomains(domains: List<String>) = Unit
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setCloseToTray(enabled: Boolean) = Unit
}
