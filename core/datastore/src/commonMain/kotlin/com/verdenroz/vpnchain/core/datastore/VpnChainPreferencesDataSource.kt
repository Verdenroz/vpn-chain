package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Preference-backed storage for the chain profile and app settings.
 * Lives in app-private storage; profile fields are credentials, so nothing
 * here may ever be logged or exported.
 */
class VpnChainPreferencesDataSource(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val profile: Flow<ChainProfile?> = dataStore.data.map { prefs ->
        prefs[PROFILE_KEY]?.let { raw ->
            runCatching { json.decodeFromString<ChainProfile>(raw) }.getOrNull()
        }
    }

    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            themeConfig = prefs[THEME_KEY]
                ?.let { raw -> runCatching { ThemeConfig.valueOf(raw) }.getOrNull() }
                ?: ThemeConfig.FOLLOW_SYSTEM,
            systemWideTun = prefs[SYSTEM_WIDE_TUN_KEY] ?: true,
            killSwitchEnabled = prefs[KILL_SWITCH_ENABLED_KEY] ?: true,
        )
    }

    /** Last public IP observed while untunnelled. Sensitive (it is the user's
     *  real address) and subject to the same no-logging rule as the profile. */
    val lastOriginIp: Flow<String?> = dataStore.data.map { prefs -> prefs[LAST_ORIGIN_IP_KEY] }

    suspend fun setLastOriginIp(ip: String) {
        dataStore.edit { it[LAST_ORIGIN_IP_KEY] = ip }
    }

    suspend fun setProfile(profile: ChainProfile) {
        dataStore.edit { it[PROFILE_KEY] = json.encodeToString(profile) }
    }

    suspend fun clearProfile() {
        dataStore.edit { it.remove(PROFILE_KEY) }
    }

    suspend fun setThemeConfig(themeConfig: ThemeConfig) {
        dataStore.edit { it[THEME_KEY] = themeConfig.name }
    }

    suspend fun setSystemWideTun(enabled: Boolean) {
        dataStore.edit { it[SYSTEM_WIDE_TUN_KEY] = enabled }
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        dataStore.edit { it[KILL_SWITCH_ENABLED_KEY] = enabled }
    }

    private companion object {
        val PROFILE_KEY = stringPreferencesKey("chain_profile_json")
        val THEME_KEY = stringPreferencesKey("theme_config")
        val SYSTEM_WIDE_TUN_KEY = booleanPreferencesKey("system_wide_tun")
        val KILL_SWITCH_ENABLED_KEY = booleanPreferencesKey("kill_switch_enabled")
        val LAST_ORIGIN_IP_KEY = stringPreferencesKey("last_origin_ip")
    }
}
