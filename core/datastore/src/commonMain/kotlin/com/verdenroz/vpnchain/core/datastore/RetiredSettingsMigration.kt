package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

private val WARP_MODE_KEY = stringPreferencesKey("warp_mode")
private val WARP_DOMAINS_KEY = stringPreferencesKey("warp_domains_json")
private const val RETIRED_MODE = "BlockedSites"

/** Drops the manual domain list and renames the mode that read it. */
internal val retiredSettingsMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        WARP_DOMAINS_KEY in currentData || currentData[WARP_MODE_KEY] == RETIRED_MODE

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            remove(WARP_DOMAINS_KEY)
            if (this[WARP_MODE_KEY] == RETIRED_MODE) this[WARP_MODE_KEY] = "Automatic"
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
