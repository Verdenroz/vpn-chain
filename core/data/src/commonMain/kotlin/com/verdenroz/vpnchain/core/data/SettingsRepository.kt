package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.common.autostart.LoginAutostart
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setThemeConfig(themeConfig: ThemeConfig)
    suspend fun setSystemWideTun(enabled: Boolean)
    suspend fun setKillSwitchEnabled(enabled: Boolean)
    suspend fun setDnsFilter(filter: DnsFilter)
    suspend fun setAutoConnectOnLaunch(enabled: Boolean)
    suspend fun setAutoReconnect(enabled: Boolean)
    suspend fun setCloseToTray(enabled: Boolean)

    /** False where the OS offers no per-user login item (Android, unpackaged runs). */
    val autostartSupported: Boolean

    /** @return failure, with a showable reason, if the OS entry can't be written. */
    suspend fun setAutoStartOnLogin(enabled: Boolean): Result<Unit>
}

internal class DefaultSettingsRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val loginAutostart: LoginAutostart,
) : SettingsRepository {
    override val autostartSupported: Boolean = loginAutostart.supported

    // Persisted only after the OS entry lands, so the toggle can never claim an
    // autostart that isn't actually registered.
    override suspend fun setAutoStartOnLogin(enabled: Boolean): Result<Unit> =
        loginAutostart.setEnabled(enabled)
            .onSuccess { preferences.setAutoStartOnLogin(enabled) }

    override val settings: Flow<UserSettings> = preferences.settings
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) =
        preferences.setThemeConfig(themeConfig)
    override suspend fun setSystemWideTun(enabled: Boolean) =
        preferences.setSystemWideTun(enabled)
    override suspend fun setKillSwitchEnabled(enabled: Boolean) =
        preferences.setKillSwitchEnabled(enabled)
    override suspend fun setDnsFilter(filter: DnsFilter) =
        preferences.setDnsFilter(filter)
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) =
        preferences.setAutoConnectOnLaunch(enabled)
    override suspend fun setAutoReconnect(enabled: Boolean) =
        preferences.setAutoReconnect(enabled)
    override suspend fun setCloseToTray(enabled: Boolean) =
        preferences.setCloseToTray(enabled)
}
