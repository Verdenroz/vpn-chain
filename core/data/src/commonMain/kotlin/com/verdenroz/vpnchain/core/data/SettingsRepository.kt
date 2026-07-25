package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setThemeConfig(themeConfig: ThemeConfig)
    suspend fun setSystemWideTun(enabled: Boolean)
    suspend fun setKillSwitchEnabled(enabled: Boolean)
    suspend fun setAutoConnectOnLaunch(enabled: Boolean)
    suspend fun setAutoReconnect(enabled: Boolean)
}

internal class DefaultSettingsRepository(
    private val preferences: VpnChainPreferencesDataSource,
) : SettingsRepository {
    override val settings: Flow<UserSettings> = preferences.settings
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) =
        preferences.setThemeConfig(themeConfig)
    override suspend fun setSystemWideTun(enabled: Boolean) =
        preferences.setSystemWideTun(enabled)
    override suspend fun setKillSwitchEnabled(enabled: Boolean) =
        preferences.setKillSwitchEnabled(enabled)
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) =
        preferences.setAutoConnectOnLaunch(enabled)
    override suspend fun setAutoReconnect(enabled: Boolean) =
        preferences.setAutoReconnect(enabled)
}
