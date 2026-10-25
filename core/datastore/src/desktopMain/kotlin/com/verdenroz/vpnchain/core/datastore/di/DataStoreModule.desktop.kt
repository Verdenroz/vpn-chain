package com.verdenroz.vpnchain.core.datastore.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.verdenroz.vpnchain.core.common.PrivateStorage
import com.verdenroz.vpnchain.core.datastore.retiredSettingsMigration
import java.io.File
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

/** Same directory the CLI uses (~/.config/vpn-chain), so state stays in one place. */
actual val dataStorePlatformModule: Module = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath(
            migrations = listOf(retiredSettingsMigration),
            produceFile = { File(PrivateStorage.configDir, "vpnchain.preferences_pb").absolutePath.toPath() },
        )
    }
}
